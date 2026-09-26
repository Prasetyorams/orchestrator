package id.jakforge.forgehub.repository;

import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Akses data pemicu terjadwal. */
@Repository
public class TriggerRepository {

    private static final String KOLOM = """
            id, name, process_name, robot_name, type, cron, interval_minutes,
            priority, timezone, runtime_type, enabled, next_run_at, last_run_at, created_at, folder_id
            """;

    private final Db db;

    public TriggerRepository(Db db) {
        this.db = db;
    }

    /** @param folderId null berarti seluruh penyewa. */
    public List<Map<String, Object>> semua(UUID tenantId, UUID folderId) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String saring = "";

        if (folderId != null) {
            saring = " AND folder_id = ?";
            args.add(folderId);
        }

        return db.rows("""
                SELECT %s FROM triggers
                 WHERE tenant_id = ?%s
                 ORDER BY enabled DESC, next_run_at
                """.formatted(KOLOM, saring), args.toArray());
    }

    /** Untuk dasbor: hanya yang aktif, paling dekat lebih dulu. */
    public List<Map<String, Object>> berikutnya(UUID tenantId, UUID folderId, int batas) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String saring = "";

        if (folderId != null) {
            saring = " AND folder_id = ?";
            args.add(folderId);
        }

        args.add(batas);

        return db.rows("""
                SELECT name, process_name, type, interval_minutes, cron, timezone,
                       enabled, next_run_at, last_run_at
                  FROM triggers
                 WHERE tenant_id = ? AND enabled%s
                 ORDER BY next_run_at
                 LIMIT ?
                """.formatted(saring), args.toArray());
    }

    // Nama pemicu unik PER FOLDER (V5), jadi setiap perubahan menyebut folder.

    public Map<String, Object> satu(UUID tenantId, UUID folderId, String nama) {
        return db.row("""
                SELECT enabled, cron, interval_minutes, timezone
                  FROM triggers WHERE tenant_id = ? AND folder_id = ? AND name = ?
                """, tenantId, folderId, nama);
    }

    public boolean ada(UUID tenantId, UUID folderId, String nama) {
        return db.exists("SELECT count(*) FROM triggers WHERE tenant_id = ? AND folder_id = ? AND name = ?",
                tenantId, folderId, nama);
    }

    /** Folder-folder tempat pemicu bernama itu ada, folder bawaan lebih dulu. */
    public List<Tempat> tempat(UUID tenantId, String nama) {
        return db.jdbc().query("""
                SELECT t.folder_id, f.is_default
                  FROM triggers t
                  JOIN folders f ON f.id = t.folder_id
                 WHERE t.tenant_id = ? AND t.name = ?
                 ORDER BY f.is_default DESC, t.created_at
                """, (rs, i) -> new Tempat(rs.getObject(1, UUID.class), rs.getBoolean(2)), tenantId, nama);
    }

    /**
     * Pemicu tetap di foldernya: proses yang boleh dipilihnya hanya proses di
     * folder yang sama, karena pemicu selalu tinggal bersama proses yang
     * dijalankannya.
     */
    public void perbarui(UUID tenantId, UUID folderId, String nama, String proses, String robot, String tipe,
                         String cron, int selang, boolean aktif, OffsetDateTime berikutnya,
                         String prioritas, String zona, String runtimeType) {
        db.exec("""
                UPDATE triggers
                   SET process_name = ?, robot_name = ?, type = ?, cron = ?,
                       interval_minutes = ?, enabled = ?, next_run_at = ?,
                       priority = ?, timezone = ?, runtime_type = ?
                 WHERE tenant_id = ? AND folder_id = ? AND name = ?
                """, proses, robot, tipe, cron, selang, aktif, berikutnya,
                prioritas, zona, runtimeType, tenantId, folderId, nama);
    }

    public void buat(UUID tenantId, UUID folderId, String nama, String proses, String robot, String tipe,
                     String cron, int selang, boolean aktif, OffsetDateTime berikutnya,
                     String prioritas, String zona, String runtimeType) {
        db.exec("""
                INSERT INTO triggers
                    (id, tenant_id, folder_id, name, process_name, robot_name, type, cron,
                     interval_minutes, enabled, next_run_at, created_at,
                     priority, timezone, runtime_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?)
                """, Db.newId(), tenantId, folderId, nama, proses, robot, tipe, cron,
                selang, aktif, berikutnya, prioritas, zona, runtimeType);
    }

    public void setAktif(UUID tenantId, UUID folderId, String nama, boolean aktif, OffsetDateTime berikutnya) {
        db.exec("""
                UPDATE triggers SET enabled = ?, next_run_at = ?
                 WHERE tenant_id = ? AND folder_id = ? AND name = ?
                """, aktif, berikutnya, tenantId, folderId, nama);
    }

    public int hapus(UUID tenantId, UUID folderId, String nama) {
        return db.exec("DELETE FROM triggers WHERE tenant_id = ? AND folder_id = ? AND name = ?",
                tenantId, folderId, nama);
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
                SELECT id, tenant_id, folder_id, name, process_name, robot_name,
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
