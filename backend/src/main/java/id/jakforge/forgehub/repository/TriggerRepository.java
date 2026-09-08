package id.jakforge.forgehub.repository;

import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Akses data pemicu terjadwal. */
@Repository
public class TriggerRepository {

    private static final String KOLOM = """
            id, name, process_name, robot_name, type, cron, interval_minutes,
            priority, timezone, runtime_type, enabled, next_run_at, last_run_at, created_at
            """;

    private final Db db;

    public TriggerRepository(Db db) {
        this.db = db;
    }

    public List<Map<String, Object>> semua(UUID tenantId) {
        return db.rows("""
                SELECT %s FROM triggers
                 WHERE tenant_id = ?
                 ORDER BY enabled DESC, next_run_at
                """.formatted(KOLOM), tenantId);
    }

    /** Untuk dasbor: hanya yang aktif, paling dekat lebih dulu. */
    public List<Map<String, Object>> berikutnya(UUID tenantId, int batas) {
        return db.rows("""
                SELECT name, process_name, type, interval_minutes, cron, timezone,
                       enabled, next_run_at, last_run_at
                  FROM triggers
                 WHERE tenant_id = ? AND enabled
                 ORDER BY next_run_at
                 LIMIT ?
                """, tenantId, batas);
    }

    public Map<String, Object> satu(UUID tenantId, String nama) {
        return db.row("""
                SELECT enabled, cron, interval_minutes, timezone
                  FROM triggers WHERE tenant_id = ? AND name = ?
                """, tenantId, nama);
    }

    public boolean ada(UUID tenantId, String nama) {
        return db.exists("SELECT count(*) FROM triggers WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    public void perbarui(UUID tenantId, String nama, String proses, String robot, String tipe,
                         String cron, int selang, boolean aktif, OffsetDateTime berikutnya,
                         String prioritas, String zona, String runtimeType) {
        db.exec("""
                UPDATE triggers
                   SET process_name = ?, robot_name = ?, type = ?, cron = ?,
                       interval_minutes = ?, enabled = ?, next_run_at = ?,
                       priority = ?, timezone = ?, runtime_type = ?
                 WHERE tenant_id = ? AND name = ?
                """, proses, robot, tipe, cron, selang, aktif, berikutnya,
                prioritas, zona, runtimeType, tenantId, nama);
    }

    public void buat(UUID tenantId, String nama, String proses, String robot, String tipe,
                     String cron, int selang, boolean aktif, OffsetDateTime berikutnya,
                     String prioritas, String zona, String runtimeType) {
        db.exec("""
                INSERT INTO triggers
                    (id, tenant_id, name, process_name, robot_name, type, cron,
                     interval_minutes, enabled, next_run_at, created_at,
                     priority, timezone, runtime_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?)
                """, Db.newId(), tenantId, nama, proses, robot, tipe, cron,
                selang, aktif, berikutnya, prioritas, zona, runtimeType);
    }

    public void setAktif(UUID tenantId, String nama, boolean aktif, OffsetDateTime berikutnya) {
        db.exec("UPDATE triggers SET enabled = ?, next_run_at = ? WHERE tenant_id = ? AND name = ?",
                aktif, berikutnya, tenantId, nama);
    }

    public int hapus(UUID tenantId, String nama) {
        return db.exec("DELETE FROM triggers WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    /**
     * Pemicu yang sudah waktunya jalan, dikunci supaya tidak diambil dua kali.
     *
     * <p>SKIP LOCKED penting kalau nanti ada lebih dari satu ForgeHub: dua
     * penjadwal yang membaca daftar yang sama akan menjadwalkan tiap pemicu dua
     * kali, dan yang terlihat adalah automasi berjalan ganda tanpa sebab.
     */
    public List<Map<String, Object>> jatuhTempo() {
        return db.rows("""
                SELECT id, tenant_id, name, process_name, robot_name,
                       interval_minutes, cron, priority, timezone
                  FROM triggers
                 WHERE enabled = TRUE
                   AND next_run_at IS NOT NULL
                   AND next_run_at <= now()
                 ORDER BY next_run_at
                 FOR UPDATE SKIP LOCKED
                """);
    }

    public void catatJalan(UUID id, OffsetDateTime berikutnya) {
        db.exec("UPDATE triggers SET last_run_at = now(), next_run_at = ? WHERE id = ?", berikutnya, id);
    }

    public void matikan(UUID id) {
        db.exec("UPDATE triggers SET enabled = FALSE, next_run_at = NULL WHERE id = ?", id);
    }

}
