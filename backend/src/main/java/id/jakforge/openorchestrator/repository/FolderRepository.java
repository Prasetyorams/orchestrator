package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.common.Uuids;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Folder dan penugasan pengguna serta robot ke dalamnya.
 *
 * <p>Isi folder — proses, antrean, aset, dan seterusnya — TIDAK diurus di sini.
 * SQL tentang proses tetap tinggal di ProcessRepository, termasuk memindahkan
 * proses ke folder lain; yang ada di sini hanya folder itu sendiri dan siapa
 * yang boleh memakainya.
 */
@Repository
@RequiredArgsConstructor
public class FolderRepository {

    /** Nama Folder Saya — sama untuk semua orang, dan ditampilkan apa adanya. */
    public static final String PERSONAL_FOLDER_NAME = "Folder Saya";

    /**
     * Kolom yang dikirim ke dasbor.
     *
     * <p>"personal" alih-alih owner_id mentah: yang perlu diketahui layar hanya
     * apakah ini Folder Saya, bukan id pemiliknya.
     */
    private static final String COLUMNS = """
            f.id, f.parent_id, f.name, f.description, f.is_default,
            f.owner_id IS NOT NULL AS personal, f.created_at
            """;

    private final Database database;

    // -----------------------------------------------------------------
    // Membaca
    // -----------------------------------------------------------------

    /** Semua folder BERSAMA sebuah penyewa — tanpa folder pribadi siapa pun. */
    public List<Map<String, Object>> findAllShared(UUID tenantId) {
        return database.queryRows("""
                SELECT %s
                  FROM folders f
                 WHERE f.tenant_id = ? AND f.owner_id IS NULL
                 ORDER BY f.is_default DESC, lower(f.name)
                """.formatted(COLUMNS), tenantId);
    }

    /** Folder Saya milik seorang pengguna, atau kosong kalau belum pernah dibuka. */
    public Optional<Map<String, Object>> findPersonal(UUID tenantId, UUID userId) {
        return database.queryRow("""
                SELECT %s
                  FROM folders f
                 WHERE f.tenant_id = ? AND f.owner_id = ?
                """.formatted(COLUMNS), tenantId, userId);
    }

    /** Satu folder, termasuk pemiliknya ({@code ownerId}) — dipakai untuk memeriksa hak. */
    public Optional<Map<String, Object>> findById(UUID tenantId, UUID folderId) {
        return database.queryRow("""
                SELECT %s, f.owner_id
                  FROM folders f
                 WHERE f.tenant_id = ? AND f.id = ?
                """.formatted(COLUMNS), tenantId, folderId);
    }

    public Optional<String> findName(UUID tenantId, UUID folderId) {
        return database.queryScalar("SELECT name FROM folders WHERE tenant_id = ? AND id = ?", tenantId, folderId)
                .map(String::valueOf);
    }

    /**
     * Semua folder untuk halaman pengelolaan, TERMASUK folder pribadi setiap
     * orang, beserta isinya.
     *
     * <p>Hitungan diambil dalam kueri yang sama supaya halaman itu bisa
     * menunjukkan folder mana yang boleh dihapus tanpa bertanya satu per satu.
     */
    public List<Map<String, Object>> findAllForManagement(UUID tenantId) {
        return database.queryRows("""
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
                """.formatted(COLUMNS), tenantId);
    }

    /** Folder tempat sebuah robot ditugaskan — yang boleh dibuka Executor-nya. */
    public Set<UUID> findRobotFolderIds(UUID tenantId, UUID robotId) {
        return toUuidSet(database.query("""
                SELECT folder_id FROM folder_robots WHERE tenant_id = ? AND robot_id = ?
                """, (rs, rowNumber) -> rs.getObject(1), tenantId, robotId));
    }

    /** Folder tempat seorang pengguna ditugaskan. */
    public Set<UUID> findAssignedFolderIds(UUID tenantId, UUID userId) {
        return toUuidSet(database.query("""
                SELECT folder_id FROM folder_users WHERE tenant_id = ? AND user_id = ?
                """, (rs, rowNumber) -> rs.getObject(1), tenantId, userId));
    }

    /** Id folder bawaan penyewa ini — dibuat oleh basis data kalau belum ada. */
    public UUID findDefaultFolderId(UUID tenantId) {
        return database.queryScalar("SELECT folder_bawaan(?)", tenantId).map(Uuids::fromColumn).orElse(null);
    }

    /**
     * Semua keturunan sebuah folder, tidak termasuk dirinya.
     *
     * <p>Dipakai sebelum memindahkan folder: induk barunya tidak boleh berada
     * di dalam cabangnya sendiri, atau cabang itu terlepas dari pohon dan
     * berputar tanpa akar.
     */
    public Set<UUID> findDescendantIds(UUID tenantId, UUID folderId) {
        return toUuidSet(database.query("""
                WITH RECURSIVE descendants AS (
                    SELECT id FROM folders WHERE tenant_id = ? AND parent_id = ?
                    UNION ALL
                    SELECT f.id FROM folders f JOIN descendants d ON f.parent_id = d.id
                )
                SELECT id FROM descendants
                """, (rs, rowNumber) -> rs.getObject(1), tenantId, folderId));
    }

    /**
     * Nama sudah dipakai saudaranya? Tanpa membedakan huruf besar, sama seperti
     * indeks unik uq_folders_nama.
     */
    public boolean isNameTaken(UUID tenantId, UUID parentId, String name, UUID excludedFolderId) {
        return database.exists("""
                SELECT count(*) FROM folders
                 WHERE tenant_id = ? AND owner_id IS NULL
                   AND parent_id IS NOT DISTINCT FROM ?
                   AND lower(name) = lower(?)
                   AND id IS DISTINCT FROM ?
                """, tenantId, parentId, name, excludedFolderId);
    }

    public boolean hasChildren(UUID tenantId, UUID folderId) {
        return database.exists("SELECT count(*) FROM folders WHERE tenant_id = ? AND parent_id = ?",
                tenantId, folderId);
    }

    /** Berapa banyak isi yang masih tinggal di folder ini. Pekerjaan tidak dihitung: itu riwayat. */
    public long countContents(UUID tenantId, UUID folderId) {
        return database.count("""
                SELECT (SELECT count(*) FROM processes WHERE tenant_id = ? AND folder_id = ?)
                     + (SELECT count(*) FROM triggers  WHERE tenant_id = ? AND folder_id = ?)
                     + (SELECT count(*) FROM queues    WHERE tenant_id = ? AND folder_id = ?)
                     + (SELECT count(*) FROM assets    WHERE tenant_id = ? AND folder_id = ?)
                     + (SELECT count(*) FROM buckets   WHERE tenant_id = ? AND folder_id = ?)
                """, tenantId, folderId, tenantId, folderId, tenantId, folderId, tenantId, folderId,
                tenantId, folderId);
    }

    public boolean hasRobots(UUID tenantId, UUID folderId) {
        return database.exists("SELECT count(*) FROM folder_robots WHERE tenant_id = ? AND folder_id = ?",
                tenantId, folderId);
    }

    // -----------------------------------------------------------------
    // Menulis
    // -----------------------------------------------------------------

    public void insert(UUID folderId, UUID tenantId, UUID parentId, String name, String description) {
        database.update("""
                INSERT INTO folders (id, tenant_id, parent_id, name, description, is_default, created_at)
                VALUES (?, ?, ?, ?, ?, FALSE, now())
                """, folderId, tenantId, parentId, name, description);
    }

    /**
     * Folder Saya untuk seorang pengguna.
     *
     * <p>ON CONFLICT: dua tab yang membukanya bersamaan tidak boleh membuat dua
     * folder pribadi, dan yang kalah cukup memakai milik yang menang.
     */
    public void insertPersonal(UUID tenantId, UUID userId) {
        database.update("""
                INSERT INTO folders (id, tenant_id, parent_id, name, description, is_default, owner_id, created_at)
                VALUES (?, ?, NULL, ?, NULL, FALSE, ?, now())
                ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), tenantId, PERSONAL_FOLDER_NAME, userId);
    }

    public void update(UUID tenantId, UUID folderId, String name, String description, UUID parentId) {
        database.update("""
                UPDATE folders SET name = ?, description = ?, parent_id = ?
                 WHERE tenant_id = ? AND id = ?
                """, name, description, parentId, tenantId, folderId);
    }

    /**
     * Subfolder baru mewarisi pengguna, robot, dan mesin induknya SAAT DIBUAT.
     *
     * <p>Bukan pewarisan yang hidup: penugasan sesudahnya diatur per folder.
     * Tanpa salinan ini, folder baru langsung tidak terlihat oleh semua orang
     * yang bekerja di induknya, dan tidak satu robot pun mau menjalankan
     * isinya — robot hanya bekerja di folder tempat mesinnya terdaftar (V12).
     */
    public void copyAssignments(UUID tenantId, UUID sourceFolderId, UUID targetFolderId) {
        database.update("""
                INSERT INTO folder_machines (folder_id, machine_id, tenant_id, created_at)
                SELECT ?, machine_id, tenant_id, now() FROM folder_machines WHERE tenant_id = ? AND folder_id = ?
                ON CONFLICT DO NOTHING
                """, targetFolderId, tenantId, sourceFolderId);

        database.update("""
                INSERT INTO folder_users (folder_id, user_id, tenant_id, created_at)
                SELECT ?, user_id, tenant_id, now() FROM folder_users WHERE tenant_id = ? AND folder_id = ?
                ON CONFLICT DO NOTHING
                """, targetFolderId, tenantId, sourceFolderId);

        database.update("""
                INSERT INTO folder_robots (folder_id, robot_id, tenant_id, created_at)
                SELECT ?, robot_id, tenant_id, now() FROM folder_robots WHERE tenant_id = ? AND folder_id = ?
                ON CONFLICT DO NOTHING
                """, targetFolderId, tenantId, sourceFolderId);
    }

    /** Riwayat pekerjaan sebuah folder yang akan dihapus pindah ke folder lain, bukan ikut hilang. */
    public void moveJobs(UUID tenantId, UUID sourceFolderId, UUID targetFolderId) {
        database.update("UPDATE jobs SET folder_id = ? WHERE tenant_id = ? AND folder_id = ?",
                targetFolderId, tenantId, sourceFolderId);
    }

    /** Penugasannya ikut terhapus lewat ON DELETE CASCADE. */
    public int delete(UUID tenantId, UUID folderId) {
        return database.update("DELETE FROM folders WHERE tenant_id = ? AND id = ?", tenantId, folderId);
    }

    // -----------------------------------------------------------------
    // Penugasan
    // -----------------------------------------------------------------

    public List<Map<String, Object>> findAssignedUsers(UUID tenantId, UUID folderId) {
        return database.queryRows("""
                SELECT u.id, u.username, u.display_name, u.role, u.is_active, fu.created_at AS assigned_at
                  FROM folder_users fu
                  JOIN users u ON u.id = fu.user_id
                 WHERE fu.tenant_id = ? AND fu.folder_id = ?
                 ORDER BY lower(u.username)
                """, tenantId, folderId);
    }

    /**
     * Robot folder itu, beserta id mesin tempat ia bekerja (null kalau
     * mesinnya belum dikenal). {@code machineRegistered}: mesin itu terdaftar
     * di folder ini (V12) — tanpa itu robotnya tidak akan mengambil job folder
     * ini, dan layar Setelan perlu mengatakannya.
     */
    public List<Map<String, Object>> findAssignedRobots(UUID tenantId, UUID folderId) {
        return database.queryRows("""
                SELECT r.id, r.name, r.machine_name, r.type, fr.created_at AS assigned_at,
                       m.id AS machine_id,
                       (m.id IS NOT NULL AND EXISTS (SELECT 1 FROM folder_machines fm
                                                      WHERE fm.folder_id = fr.folder_id
                                                        AND fm.machine_id = m.id)) AS machine_registered
                  FROM folder_robots fr
                  JOIN robots r ON r.id = fr.robot_id
                  LEFT JOIN LATERAL (SELECT mm.id FROM machines mm
                                      WHERE mm.id = r.machine_id
                                         OR (r.machine_id IS NULL AND mm.tenant_id = r.tenant_id
                                             AND mm.name = r.machine_name)
                                      LIMIT 1) m ON TRUE
                 WHERE fr.tenant_id = ? AND fr.folder_id = ?
                 ORDER BY lower(r.name)
                """, tenantId, folderId);
    }

    public int assignUser(UUID tenantId, UUID folderId, UUID userId) {
        return database.update("""
                INSERT INTO folder_users (folder_id, user_id, tenant_id, created_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT DO NOTHING
                """, folderId, userId, tenantId);
    }

    public int unassignUser(UUID tenantId, UUID folderId, UUID userId) {
        return database.update("DELETE FROM folder_users WHERE tenant_id = ? AND folder_id = ? AND user_id = ?",
                tenantId, folderId, userId);
    }

    public int assignRobot(UUID tenantId, UUID folderId, UUID robotId) {
        return database.update("""
                INSERT INTO folder_robots (folder_id, robot_id, tenant_id, created_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT DO NOTHING
                """, folderId, robotId, tenantId);
    }

    public int unassignRobot(UUID tenantId, UUID folderId, UUID robotId) {
        return database.update("DELETE FROM folder_robots WHERE tenant_id = ? AND folder_id = ? AND robot_id = ?",
                tenantId, folderId, robotId);
    }

    private static Set<UUID> toUuidSet(List<Object> columnValues) {
        Set<UUID> ids = new HashSet<>();
        for (Object value : columnValues) ids.add(Uuids.fromColumn(value));

        return ids;
    }
}
