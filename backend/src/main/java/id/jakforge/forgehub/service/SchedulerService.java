package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.Cron;
import id.jakforge.forgehub.model.Severity;
import id.jakforge.forgehub.repository.CatalogRepository;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.JobRepository;
import id.jakforge.forgehub.repository.LogRepository;
import id.jakforge.forgehub.repository.RobotRepository;
import id.jakforge.forgehub.repository.TriggerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
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
@Service
@EnableScheduling
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    /**
     * Setelah berapa lama pekerjaan yang robotnya diam dianggap terputus.
     *
     * <p>Dua kali batas putus robot, bukan satu kali: robot yang baru saja
     * melewatkan satu denyut belum tentu mati, dan menandai pekerjaannya gagal
     * terlalu cepat menghasilkan kegagalan palsu untuk automasi yang sebenarnya
     * masih berjalan.
     */
    private static final int TERPUTUS_SETELAH_DETIK = RobotRepository.PUTUS_SETELAH_DETIK * 2;

    private final TriggerRepository pemicu;
    private final CatalogRepository katalog;
    private final JobRepository jobs;
    private final LogRepository catatan;

    public SchedulerService(TriggerRepository pemicu, CatalogRepository katalog,
                            JobRepository jobs, LogRepository catatan) {
        this.pemicu = pemicu;
        this.katalog = katalog;
        this.jobs = jobs;
        this.catatan = catatan;
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

    @Transactional
    public void jalankanYangJatuhTempo() {
        List<Map<String, Object>> jatuhTempo = pemicu.jatuhTempo();

        for (Map<String, Object> t : jatuhTempo) {
            UUID id = Db.uuid((String) t.get("id"));
            UUID tenantId = Db.uuid((String) t.get("tenantId"));
            String nama = (String) t.get("name");
            String proses = (String) t.get("processName");
            String cron = (String) t.get("cron");
            int selang = Math.max(1, ((Number) t.get("intervalMinutes")).intValue());

            // Proses bisa saja sudah dihapus setelah pemicunya dibuat.
            // Pemicunya dimatikan, bukan diam-diam gagal tiap 30 detik
            // selamanya — kegagalan yang berulang tanpa henti adalah kegagalan
            // yang berhenti dibaca orang.
            if (!katalog.adaProses(tenantId, proses)) {
                matikan(id, tenantId, nama,
                        "Pemicu '" + nama + "' menunjuk proses '" + proses + "' yang sudah tidak ada.");
                continue;
            }

            OffsetDateTime berikutnya = TriggerService.hitungBerikutnya(
                    cron, selang, Cron.zona((String) t.get("timezone")));

            if (berikutnya == null) {
                matikan(id, tenantId, nama,
                        "Pemicu '" + nama + "' memakai ekspresi cron yang tidak pernah cocok: " + cron);
                continue;
            }

            UUID jobId = Db.newId();
            Object prioritas = t.get("priority");

            jobs.buat(jobId, tenantId, proses, (String) t.get("robotName"), null,
                    "Trigger", prioritas == null ? "Normal" : String.valueOf(prioritas),
                    "Dijadwalkan oleh pemicu '" + nama + "'.", null);

            pemicu.catatJalan(id, berikutnya);

            catatan.tulisSistem(tenantId,
                    "Pemicu '" + nama + "' menjadwalkan " + proses + ".", proses, jobId);

            log.info("Pemicu '{}' menjadwalkan {}; berikutnya {}.", nama, proses, berikutnya);
        }
    }

    /**
     * Pekerjaan yang robotnya menghilang.
     *
     * <p>Robot yang mati di tengah jalan tidak pernah melaporkan hasil akhir,
     * jadi pekerjaannya akan berstatus RUNNING selamanya — dan kartu "berjalan"
     * di dasbor terus menghitungnya.
     */
    @Transactional
    public void gagalkanYangTerputus() {
        for (Map<String, Object> job : jobs.gagalkanYangTerputus(TERPUTUS_SETELAH_DETIK)) {
            UUID tenantId = Db.uuid((String) job.get("tenantId"));
            Object robot = job.get("robotName");

            catatan.catatPeringatan(tenantId, Severity.Error, "Pekerjaan terputus",
                    job.get("processName") + " dihentikan karena robot '"
                            + (robot == null ? "?" : robot) + "' tidak lagi terhubung.",
                    "jobs");

            log.warn("Pekerjaan {} ditandai FAULTED: robot {} berhenti berdenyut.",
                    job.get("id"), robot);
        }
    }

    private void matikan(UUID id, UUID tenantId, String nama, String sebab) {
        pemicu.matikan(id);

        catatan.catatPeringatan(tenantId, Severity.Warning, "Pemicu dimatikan", sebab, "triggers");

        log.warn(sebab);
    }
}
