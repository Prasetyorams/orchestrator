package id.jakforge.forgehub.repository;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Jejak audit: siapa mengubah apa, dan kapan. */
@Repository
public class AuditRepository {

    private final Db db;

    public AuditRepository(Db db) {
        this.db = db;
    }

    /**
     * Catat satu perubahan.
     *
     * <p>Teks dipotong ke panjang kolomnya di sini, bukan dibiarkan ditolak
     * basis data: jejak yang gagal ditulis karena nama berkasnya terlalu
     * panjang adalah jejak yang hilang tanpa suara.
     */
    public void catat(UUID tenantId, String username, String komponen, String aksi,
                      String sasaran, String rincian) {
        db.exec("""
                INSERT INTO audit_logs (tenant_id, username, component, action, target, detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, tenantId, potong(username, 80), potong(komponen, 60), potong(aksi, 60),
                potong(sasaran, 400), potong(rincian, 600));
    }

    /**
     * Daftar terbaru lebih dulu.
     *
     * @param komponen hanya komponen ini (Proses, Aset, ...); null berarti semua
     * @param cari     potongan nama pengguna atau sasaran, tanpa membedakan huruf besar
     */
    public List<Map<String, Object>> daftar(UUID tenantId, String komponen, String cari, int batas) {
        List<String> where = new ArrayList<>(List.of("tenant_id = ?"));
        List<Object> args = new ArrayList<>(List.of(tenantId));

        if (komponen != null && !komponen.isBlank()) {
            where.add("component = ?");
            args.add(komponen);
        }

        if (cari != null && !cari.isBlank()) {
            where.add("(username ILIKE ? OR target ILIKE ?)");
            String pola = "%" + cari.trim()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_") + "%";
            args.add(pola);
            args.add(pola);
        }

        args.add(batas);

        return db.rows("""
                SELECT id, username, component, action, target, detail, created_at
                  FROM audit_logs
                 WHERE %s
                 ORDER BY id DESC
                 LIMIT ?
                """.formatted(String.join(" AND ", where)), args.toArray());
    }

    /** Komponen yang pernah tercatat, untuk pilihan penyaring. */
    public List<Map<String, Object>> komponen(UUID tenantId) {
        return db.rows("""
                SELECT component, count(*) AS total
                  FROM audit_logs WHERE tenant_id = ?
                 GROUP BY component
                 ORDER BY component
                """, tenantId);
    }

    /** Penyewa pemilik nama pengguna itu — untuk mencatat masuk, saat belum ada token. */
    public UUID penyewaPengguna(String username) {
        Object id = db.scalar("SELECT tenant_id FROM users WHERE username = ? LIMIT 1", username);
        if (id == null) return null;

        return id instanceof UUID u ? u : Db.uuid(String.valueOf(id));
    }

    public String namaFolder(UUID tenantId, UUID id) {
        Object v = db.scalar("SELECT name FROM folders WHERE tenant_id = ? AND id = ?", tenantId, id);
        return v == null ? null : String.valueOf(v);
    }

    public String prosesPekerjaan(UUID tenantId, UUID id) {
        Object v = db.scalar("SELECT process_name FROM jobs WHERE tenant_id = ? AND id = ?", tenantId, id);
        return v == null ? null : String.valueOf(v);
    }

    private static String potong(String teks, int panjang) {
        if (teks == null) return null;
        return teks.length() <= panjang ? teks : teks.substring(0, panjang - 1) + "…";
    }
}
