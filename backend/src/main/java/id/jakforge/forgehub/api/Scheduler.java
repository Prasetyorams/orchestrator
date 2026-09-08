package id.jakforge.forgehub.api;

import id.jakforge.forgehub.data.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Penjalan pemicu terjadwal.
 *
 * <p>Diperiksa tiap 30 detik. Selang sependek itu terasa boros, tapi biayanya
 * satu kueri berindeks — dan alternatifnya, memeriksa tiap menit, membuat
 * pemicu yang dipasang untuk "tiap 1 menit" meleset separuh waktu.
 *
 * <p>Pemicu yang terlewat TIDAK dikejar. Kalau ForgeHub mati semalaman, yang
 * dijalankan saat menyala lagi adalah satu kali, bukan dua belas kali sekaligus
 * menumpuk di robot yang sama.
 */
@Component
@EnableScheduling
public class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    /**
     * Setelah berapa lama pekerjaan yang robotnya diam dianggap terputus.
     *
     * <p>Dua kali batas putus robot, bukan satu kali: robot yang baru saja
     * melewatkan satu denyut belum tentu mati, dan menandai pekerjaannya gagal
     * terlalu cepat menghasilkan kegagalan palsu untuk automasi yang sebenarnya
     * masih berjalan.
     */
    private static final int TERPUTUS_SETELAH_DETIK = 90;

    private final Db db;

    public Scheduler(Db db) {
        this.db = db;
    }

    /**
     * initialDelay memberi basis data dan Flyway waktu untuk selesai sebelum
     * pemeriksaan pertama. Tanpa itu, putaran pertama berebut dengan penyiapan
     * dan mencatat kegagalan yang bukan kegagalan.
     */
    @Scheduled(initialDelay = 15_000, fixedDelay = 30_000)
    public void putaran() {
        try {
            jalankanYangJatuhTempo();
        } catch (Exception e) {
            // Satu putaran yang gagal tidak boleh menghentikan penjadwal
            // selamanya. Dicatat, lalu dicoba lagi 30 detik kemudian.
            log.error("Penjadwal gagal saat menjalankan pemicu.", e);
        }

        try {
            gagalkanYangTerputus();
        } catch (Exception e) {
            log.error("Penjadwal gagal saat memeriksa pekerjaan terputus.", e);
        }
    }

    // -----------------------------------------------------------------

    @Transactional
    public void jalankanYangJatuhTempo() {
        List<Map<String, Object>> jatuhTempo = db.rows("""
                SELECT id, tenant_id, name, process_name, robot_name,
                       interval_minutes, cron, priority, timezone
                  FROM triggers
                 WHERE enabled = TRUE
                   AND next_run_at IS NOT NULL
                   AND next_run_at <= now()
                 ORDER BY next_run_at
                 FOR UPDATE SKIP LOCKED
                """);

        for (Map<String, Object> pemicu : jatuhTempo) {
            UUID id = Db.uuid((String) pemicu.get("id"));
            UUID tenantId = Db.uuid((String) pemicu.get("tenantId"));
            String nama = (String) pemicu.get("name");
            String proses = (String) pemicu.get("processName");
            String cron = (String) pemicu.get("cron");
            int selang = Math.max(1, ((Number) pemicu.get("intervalMinutes")).intValue());

            // Proses bisa saja sudah dihapus setelah pemicunya dibuat.
            // Pemicunya dimatikan, bukan diam-diam gagal tiap 30 detik
            // selamanya — kegagalan yang berulang tanpa henti adalah kegagalan
            // yang berhenti dibaca orang.
            if (!db.exists("SELECT count(*) FROM processes WHERE tenant_id = ? AND name = ?", tenantId, proses)) {
                matikan(id, tenantId, nama,
                        "Pemicu '" + nama + "' menunjuk proses '" + proses + "' yang sudah tidak ada.");
                continue;
            }

            OffsetDateTime berikutnya = TriggersApi.hitungBerikutnya(
                    cron, selang, Cron.zona((String) pemicu.get("timezone")));

            if (berikutnya == null) {
                matikan(id, tenantId, nama,
                        "Pemicu '" + nama + "' memakai ekspresi cron yang tidak pernah cocok: " + cron);
                continue;
            }

            UUID jobId = Db.newId();
            Object prioritas = pemicu.get("priority");

            db.exec("""
                    INSERT INTO jobs
                        (id, tenant_id, process_name, robot_name, state, source, priority,
                         progress, info, created_at)
                    VALUES (?, ?, ?, ?, 'PENDING', 'Trigger', ?, 0, ?, now())
                    """, jobId, tenantId, proses, pemicu.get("robotName"),
                    prioritas == null ? "Normal" : prioritas,
                    "Dijadwalkan oleh pemicu '" + nama + "'.");

            db.exec("UPDATE triggers SET last_run_at = now(), next_run_at = ? WHERE id = ?",
                    berikutnya, id);

            db.exec("""
                    INSERT INTO logs (tenant_id, level, message, process_name, job_id, logged_at)
                    VALUES (?, 'INFO', ?, ?, ?, now())
                    """, tenantId, "Pemicu '" + nama + "' menjadwalkan " + proses + ".", proses, jobId);

            log.info("Pemicu '{}' menjadwalkan {}; berikutnya {}.", nama, proses, berikutnya);
        }
    }

    private void matikan(UUID id, UUID tenantId, String nama, String sebab) {
        db.exec("UPDATE triggers SET enabled = FALSE, next_run_at = NULL WHERE id = ?", id);

        Peringatan.catat(db, tenantId, "Warning", "Pemicu dimatikan", sebab, "triggers");

        log.warn(sebab);
    }

    /**
     * Pekerjaan yang robotnya menghilang.
     *
     * <p>Robot yang mati di tengah jalan tidak pernah melaporkan hasil akhir,
     * jadi pekerjaannya akan berstatus RUNNING selamanya — dan kartu "berjalan"
     * di dasbor terus menghitungnya. Setelah robotnya dinyatakan putus,
     * pekerjaannya ditandai gagal, dengan sebab yang jelas.
     */
    @Transactional
    public void gagalkanYangTerputus() {
        // RETURNING dipakai supaya peringatan hanya dibuat untuk baris yang
        // BENAR-BENAR berubah. Memilih dulu lalu memperbarui membuka celah:
        // pekerjaan yang selesai di antara kedua langkah itu tetap mendapat
        // peringatan "terputus" padahal berhasil.
        List<Map<String, Object>> terputus = db.rows("""
                UPDATE jobs j
                   SET state = 'FAULTED',
                       info = 'Robot ' || COALESCE(j.robot_name, '?')
                              || ' berhenti berdenyut saat pekerjaan masih berjalan.',
                       ended_at = now()
                  FROM (SELECT j2.id
                          FROM jobs j2
                          LEFT JOIN robots r
                                 ON r.tenant_id = j2.tenant_id AND r.name = j2.robot_name
                         WHERE j2.state = 'RUNNING'
                           AND (r.last_heartbeat_at IS NULL
                                OR now() - r.last_heartbeat_at > interval '%d seconds')
                         FOR UPDATE OF j2 SKIP LOCKED) AS pilih
                 WHERE j.id = pilih.id AND j.state = 'RUNNING'
             RETURNING j.id, j.tenant_id, j.process_name, j.robot_name
                """.formatted(TERPUTUS_SETELAH_DETIK));

        for (Map<String, Object> job : terputus) {
            UUID tenantId = Db.uuid((String) job.get("tenantId"));
            Object robot = job.get("robotName");

            Peringatan.catat(db, tenantId, "Error", "Pekerjaan terputus",
                    job.get("processName") + " dihentikan karena robot '"
                            + (robot == null ? "?" : robot) + "' tidak lagi terhubung.",
                    "jobs");

            log.warn("Pekerjaan {} ditandai FAULTED: robot {} berhenti berdenyut.",
                    job.get("id"), robot);
        }
    }
}
