package id.jakforge.forgehub.repository;

import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Folder dan penugasan pengguna serta robot ke dalamnya.
 *
 * <p>Isi folder — proses, antrean, aset, dan seterusnya — TIDAK diurus di sini.
 * SQL tentang proses tetap tinggal di CatalogRepository, termasuk memindahkan
 * proses ke folder lain; yang ada di sini hanya folder itu sendiri dan siapa
 * yang boleh memakainya.
 */
@Repository
public class FolderRepository {

    /**
     * Kolom yang dikirim ke dasbor.
     *
     * <p>"personal" alih-alih owner_id mentah: yang perlu diketahui layar hanya
     * apakah ini Folder Saya, bukan id pemiliknya.
     */
    private static final String KOLOM = """
            f.id, f.parent_id, f.name, f.description, f.is_default,
            f.owner_id IS NOT NULL AS personal, f.created_at
            """;

    private final Db db;

    public FolderRepository(Db db) {
        this.db = db;
    }

    // -----------------------------------------------------------------
    // Membaca
    // -----------------------------------------------------------------

    /** Semua folder BERSAMA sebuah penyewa — tanpa folder pribadi siapa pun. */
    public List<Map<String, Object>> semua(UUID tenantId) {
        return db.rows("""
                SELECT %s
                  FROM folders f
                 WHERE f.tenant_id = ? AND f.owner_id IS NULL
                 ORDER BY f.is_default DESC, lower(f.name)
                """.formatted(KOLOM), tenantId);
    }

    /** Folder Saya milik seorang pengguna, atau null kalau belum pernah dibuka. */
    public Map<String, Object> pribadi(UUID tenantId, UUID userId) {
        return db.row("""
                SELECT %s
                  FROM folders f
                 WHERE f.tenant_id = ? AND f.owner_id = ?
                """.formatted(KOLOM), tenantId, userId);
    }

    /** Satu folder, termasuk pemiliknya — dipakai untuk memeriksa hak. */
    public Map<String, Object> satu(UUID tenantId, UUID id) {
        return db.row("""
                SELECT %s, f.owner_id
                  FROM folders f
                 WHERE f.tenant_id = ? AND f.id = ?
                """.formatted(KOLOM), tenantId, id);
    }

    /**
     * Semua folder untuk halaman pengelolaan, TERMASUK folder pribadi setiap
     * orang, beserta isinya.
     *
     * <p>Hitungan diambil dalam kueri yang sama supaya halaman itu bisa
     * menunjukkan folder mana yang boleh dihapus tanpa bertanya satu per satu.
     */
    public List<Map<String, Object>> kelola(UUID tenantId) {
        return db.rows("""
                SELECT %s,
                       u.username AS owner_username,
                       (SELECT count(*) FROM folders c    WHERE c.parent_id = f.id)  AS child_count,
                       (SELECT count(*) FROM processes x  WHERE x.folder_id = f.id)  AS process_count,
                       (SELECT count(*) FROM triggers x   WHERE x.folder_id = f.id)  AS trigger_count,
                       (SELECT count(*) FROM queues x     WHERE x.folder_id = f.id)  AS queue_count,
                       (SELECT count(*) FROM assets x     WHERE x.folder_id = f.id)  AS asset_count,
                       (SELECT count(*) FROM buckets x    WHERE x.folder_id = f.id)  AS bucket_count,
                       (SELECT count(*) FROM folder_users x  WHERE x.folder_id = f.id) AS user_count,
                       (SELECT count(*) FROM folder_robots x WHERE x.folder_id = f.id) AS robot_count
                  FROM folders f
                  LEFT JOIN users u ON u.id = f.owner_id
                 WHERE f.tenant_id = ?
                 ORDER BY f.owner_id IS NOT NULL, f.is_default DESC, lower(f.name)
                """.formatted(KOLOM), tenantId);
    }

    /** Folder tempat seorang pengguna ditugaskan. */
    public Set<UUID> ditugaskan(UUID tenantId, UUID userId) {
        List<Object> id = db.jdbc().query("""
                SELECT folder_id FROM folder_users WHERE tenant_id = ? AND user_id = ?
                """, (rs, i) -> rs.getObject(1), tenantId, userId);

        Set<UUID> hasil = new HashSet<>();
        for (Object o : id) hasil.add(o instanceof UUID u ? u : Db.uuid(String.valueOf(o)));

        return hasil;
    }

    /** Id folder bawaan penyewa ini — dibuat oleh basis data kalau belum ada. */
    public UUID bawaan(UUID tenantId) {
        Object id = db.scalar("SELECT folder_bawaan(?)", tenantId);
        return id instanceof UUID u ? u : Db.uuid(String.valueOf(id));
    }

    /**
     * Semua keturunan sebuah folder, tidak termasuk dirinya.
     *
     * <p>Dipakai sebelum memindahkan folder: induk barunya tidak boleh berada
     * di dalam cabangnya sendiri, atau cabang itu terlepas dari pohon dan
     * berputar tanpa akar.
     */
    public Set<UUID> keturunan(UUID tenantId, UUID id) {
        List<Object> baris = db.jdbc().query("""
                WITH RECURSIVE cabang AS (
                    SELECT id FROM folders WHERE tenant_id = ? AND parent_id = ?
                    UNION ALL
                    SELECT f.id FROM folders f JOIN cabang c ON f.parent_id = c.id
                )
                SELECT id FROM cabang
                """, (rs, i) -> rs.getObject(1), tenantId, id);

        Set<UUID> hasil = new HashSet<>();
        for (Object o : baris) hasil.add(o instanceof UUID u ? u : Db.uuid(String.valueOf(o)));

        return hasil;
    }

    /**
     * Nama sudah dipakai saudaranya? Tanpa membedakan huruf besar, sama seperti
     * indeks unik uq_folders_nama.
     */
    public boolean namaDipakai(UUID tenantId, UUID parentId, String nama, UUID kecuali) {
        return db.exists("""
                SELECT count(*) FROM folders
                 WHERE tenant_id = ? AND owner_id IS NULL
                   AND parent_id IS NOT DISTINCT FROM ?
                   AND lower(name) = lower(?)
                   AND id IS DISTINCT FROM ?
                """, tenantId, parentId, nama, kecuali);
    }

    public boolean adaAnak(UUID tenantId, UUID id) {
        return db.exists("SELECT count(*) FROM folders WHERE tenant_id = ? AND parent_id = ?", tenantId, id);
    }

    /** Berapa banyak isi yang masih tinggal di folder ini. Pekerjaan tidak dihitung: itu riwayat. */
    public long jumlahIsi(UUID tenantId, UUID id) {
        return db.count("""
                SELECT (SELECT count(*) FROM processes WHERE tenant_id = ? AND folder_id = ?)
                     + (SELECT count(*) FROM triggers  WHERE tenant_id = ? AND folder_id = ?)
                     + (SELECT count(*) FROM queues    WHERE tenant_id = ? AND folder_id = ?)
                     + (SELECT count(*) FROM assets    WHERE tenant_id = ? AND folder_id = ?)
                     + (SELECT count(*) FROM buckets   WHERE tenant_id = ? AND folder_id = ?)
                """, tenantId, id, tenantId, id, tenantId, id, tenantId, id, tenantId, id);
    }

    public boolean adaRobot(UUID tenantId, UUID folderId) {
        return db.exists("SELECT count(*) FROM folder_robots WHERE tenant_id = ? AND folder_id = ?",
                tenantId, folderId);
    }

    // -----------------------------------------------------------------
    // Menulis
    // -----------------------------------------------------------------

    public void buat(UUID id, UUID tenantId, UUID parentId, String nama, String keterangan) {
        db.exec("""
                INSERT INTO folders (id, tenant_id, parent_id, name, description, is_default, created_at)
                VALUES (?, ?, ?, ?, ?, FALSE, now())
                """, id, tenantId, parentId, nama, keterangan);
    }

    /**
     * Folder Saya untuk seorang pengguna.
     *
     * <p>ON CONFLICT: dua tab yang membukanya bersamaan tidak boleh membuat dua
     * folder pribadi, dan yang kalah cukup memakai milik yang menang.
     */
    public void buatPribadi(UUID tenantId, UUID userId) {
        db.exec("""
                INSERT INTO folders (id, tenant_id, parent_id, name, description, is_default, owner_id, created_at)
                VALUES (?, ?, NULL, 'Folder Saya', NULL, FALSE, ?, now())
                ON CONFLICT DO NOTHING
                """, Db.newId(), tenantId, userId);
    }

    public void ubah(UUID tenantId, UUID id, String nama, String keterangan, UUID parentId) {
        db.exec("""
                UPDATE folders SET name = ?, description = ?, parent_id = ?
                 WHERE tenant_id = ? AND id = ?
                """, nama, keterangan, parentId, tenantId, id);
    }

    /**
     * Subfolder baru mewarisi pengguna dan robot induknya SAAT DIBUAT.
     *
     * <p>Bukan pewarisan yang hidup: penugasan sesudahnya diatur per folder.
     * Tanpa salinan ini, folder baru langsung tidak terlihat oleh semua orang
     * yang bekerja di induknya, dan tidak satu robot pun mau menjalankan
     * isinya.
     */
    public void salinPenugasan(UUID tenantId, UUID dari, UUID ke) {
        db.exec("""
                INSERT INTO folder_users (folder_id, user_id, tenant_id, created_at)
                SELECT ?, user_id, tenant_id, now() FROM folder_users WHERE tenant_id = ? AND folder_id = ?
                ON CONFLICT DO NOTHING
                """, ke, tenantId, dari);

        db.exec("""
                INSERT INTO folder_robots (folder_id, robot_id, tenant_id, created_at)
                SELECT ?, robot_id, tenant_id, now() FROM folder_robots WHERE tenant_id = ? AND folder_id = ?
                ON CONFLICT DO NOTHING
                """, ke, tenantId, dari);
    }

    /** Riwayat pekerjaan sebuah folder yang akan dihapus pindah ke folder lain, bukan ikut hilang. */
    public void pindahkanPekerjaan(UUID tenantId, UUID dari, UUID ke) {
        db.exec("UPDATE jobs SET folder_id = ? WHERE tenant_id = ? AND folder_id = ?", ke, tenantId, dari);
    }

    /** Penugasannya ikut terhapus lewat ON DELETE CASCADE. */
    public int hapus(UUID tenantId, UUID id) {
        return db.exec("DELETE FROM folders WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    // -----------------------------------------------------------------
    // Penugasan
    // -----------------------------------------------------------------

    public List<Map<String, Object>> pengguna(UUID tenantId, UUID folderId) {
        return db.rows("""
                SELECT u.id, u.username, u.display_name, u.role, u.is_active, fu.created_at AS assigned_at
                  FROM folder_users fu
                  JOIN users u ON u.id = fu.user_id
                 WHERE fu.tenant_id = ? AND fu.folder_id = ?
                 ORDER BY lower(u.username)
                """, tenantId, folderId);
    }

    public List<Map<String, Object>> robot(UUID tenantId, UUID folderId) {
        return db.rows("""
                SELECT r.id, r.name, r.machine_name, r.type, fr.created_at AS assigned_at
                  FROM folder_robots fr
                  JOIN robots r ON r.id = fr.robot_id
                 WHERE fr.tenant_id = ? AND fr.folder_id = ?
                 ORDER BY lower(r.name)
                """, tenantId, folderId);
    }

    public int tugaskanPengguna(UUID tenantId, UUID folderId, UUID userId) {
        return db.exec("""
                INSERT INTO folder_users (folder_id, user_id, tenant_id, created_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT DO NOTHING
                """, folderId, userId, tenantId);
    }

    public int lepasPengguna(UUID tenantId, UUID folderId, UUID userId) {
        return db.exec("DELETE FROM folder_users WHERE tenant_id = ? AND folder_id = ? AND user_id = ?",
                tenantId, folderId, userId);
    }

    public int tugaskanRobot(UUID tenantId, UUID folderId, UUID robotId) {
        return db.exec("""
                INSERT INTO folder_robots (folder_id, robot_id, tenant_id, created_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT DO NOTHING
                """, folderId, robotId, tenantId);
    }

    public int lepasRobot(UUID tenantId, UUID folderId, UUID robotId) {
        return db.exec("DELETE FROM folder_robots WHERE tenant_id = ? AND folder_id = ? AND robot_id = ?",
                tenantId, folderId, robotId);
    }

    /** Id pengguna bernama itu di penyewa ini, atau null. */
    public UUID idPengguna(UUID tenantId, String username) {
        Object id = db.scalar("SELECT id FROM users WHERE tenant_id = ? AND username = ?", tenantId, username);
        if (id == null) return null;

        return id instanceof UUID u ? u : Db.uuid(String.valueOf(id));
    }

    /** Id robot bernama itu di penyewa ini, atau null. */
    public UUID idRobot(UUID tenantId, String nama) {
        Object id = db.scalar("SELECT id FROM robots WHERE tenant_id = ? AND name = ?", tenantId, nama);
        if (id == null) return null;

        return id instanceof UUID u ? u : Db.uuid(String.valueOf(id));
    }
}
