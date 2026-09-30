package id.jakforge.openorchestrator.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Proses: paket mana yang dijalankan, di folder mana.
 *
 * <p>Nama proses unik PER FOLDER (V5), jadi hampir setiap perubahan menyebut
 * foldernya.
 */
@Repository
@RequiredArgsConstructor
public class ProcessRepository {

    /** Pekerjaan yang belum selesai, termasuk yang dipegang Robot Agent. */
    private static final String ACTIVE_STATES =
            "('PENDING', 'ASSIGNED', 'PREPARING_SESSION', 'RUNNING', 'STOPPING', 'UNRESPONSIVE')";

    private final Database database;

    /**
     * Setelan jalan proses ini: batas waktu, jeda berhenti, dan percobaan
     * ulang robot unattended, serta prioritas bawaan job-nya.
     *
     * @param timeoutSeconds null = tidak diubah; 0 = tanpa batas waktu
     * @param priority       null = tidak diubah
     */
    public void updateRunSettings(UUID tenantId, UUID folderId, String name, Integer timeoutSeconds,
                                  Integer stopGraceSeconds, Integer maxRetries, String priority) {
        database.update("""
                UPDATE processes
                   SET timeout_seconds = CASE WHEN ? THEN NULLIF(?, 0) ELSE timeout_seconds END,
                       stop_grace_seconds = COALESCE(?, stop_grace_seconds),
                       max_retries = COALESCE(?, max_retries),
                       priority = COALESCE(?, priority)
                 WHERE tenant_id = ? AND folder_id = ? AND name = ?
                """, timeoutSeconds != null, timeoutSeconds == null ? 0 : timeoutSeconds, stopGraceSeconds,
                maxRetries, priority, tenantId, folderId, name);
    }

    /** Prioritas bawaan job proses ini. */
    public Optional<String> findPriority(UUID tenantId, UUID folderId, String name) {
        return database.queryScalar("SELECT priority FROM processes WHERE tenant_id = ? AND folder_id = ? AND name = ?",
                tenantId, folderId, name).map(String::valueOf);
    }

    /**
     * Daftar proses, beserta pekerjaannya yang BELUM selesai.
     *
     * <p>active_jobs dan active_state yang membuat tombol Jalankan di halaman
     * Proses mati selama prosesnya masih berjalan, lalu hidup lagi begitu
     * selesai. Keadaannya dipilih yang paling jauh: RUNNING mengalahkan
     * STOPPING, STOPPING mengalahkan PENDING.
     *
     * <p>Pekerjaan dicocokkan lewat nama DAN folder: proses bernama sama di
     * folder lain adalah proses lain, dengan riwayatnya sendiri.
     *
     * @param folderId null berarti seluruh penyewa
     */
    public List<Map<String, Object>> findAll(UUID tenantId, UUID folderId) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String folderFilter = "";

        if (folderId != null) {
            folderFilter = " AND p.folder_id = ?";
            args.add(folderId);
        }

        return database.queryRows("""
                SELECT p.id, p.name, p.package_name, p.package_version, p.environment,
                       p.description, p.created_at, p.folder_id,
                       p.timeout_seconds, p.stop_grace_seconds, p.max_retries, p.priority,
                       (SELECT count(*) FROM jobs j
                         WHERE j.tenant_id = p.tenant_id AND j.folder_id = p.folder_id
                           AND j.process_name = p.name) AS job_count,
                       (SELECT max(j.created_at) FROM jobs j
                         WHERE j.tenant_id = p.tenant_id AND j.folder_id = p.folder_id
                           AND j.process_name = p.name) AS last_run_at,
                       (SELECT count(*) FROM jobs j
                         WHERE j.tenant_id = p.tenant_id AND j.folder_id = p.folder_id
                           AND j.process_name = p.name
                           AND j.state IN %2$s) AS active_jobs,
                       (SELECT j.state FROM jobs j
                         WHERE j.tenant_id = p.tenant_id AND j.folder_id = p.folder_id
                           AND j.process_name = p.name
                           AND j.state IN %2$s
                         ORDER BY CASE j.state WHEN 'RUNNING' THEN 0 WHEN 'STOPPING' THEN 1 ELSE 2 END
                         LIMIT 1) AS active_state
                  FROM processes p
                 WHERE p.tenant_id = ?%1$s
                 ORDER BY p.name, p.created_at
                """.formatted(folderFilter, ACTIVE_STATES), args.toArray());
    }

    /** Ada proses bernama itu di folder mana pun. */
    public boolean existsByName(UUID tenantId, String name) {
        return database.exists("SELECT count(*) FROM processes WHERE tenant_id = ? AND name = ?", tenantId, name);
    }

    public boolean existsInFolder(UUID tenantId, String name, UUID folderId) {
        return database.exists("SELECT count(*) FROM processes WHERE tenant_id = ? AND folder_id = ? AND name = ?",
                tenantId, folderId, name);
    }

    /**
     * Folder-folder tempat proses bernama itu tinggal: folder bawaan lebih
     * dulu, lalu yang paling lama — urutan yang sama dengan pemicu basis data
     * {@code isi_folder_dari_proses} di V5.
     */
    public List<FolderLocation> findLocations(UUID tenantId, String name) {
        return database.query("""
                SELECT p.folder_id, f.is_default
                  FROM processes p
                  JOIN folders f ON f.id = p.folder_id
                 WHERE p.tenant_id = ? AND p.name = ?
                 ORDER BY f.is_default DESC, p.created_at
                """, (rs, rowNumber) -> new FolderLocation(rs.getObject(1, UUID.class), rs.getBoolean(2)),
                tenantId, name);
    }

    /** COALESCE: medan yang tidak dikirim tidak menghapus nilai yang sudah ada. */
    public void update(UUID tenantId, UUID folderId, String name, String packageName, String packageVersion,
                       String environment, String description) {
        database.update("""
                UPDATE processes
                   SET package_name = COALESCE(?, package_name),
                       package_version = COALESCE(?, package_version),
                       environment = COALESCE(?, environment),
                       description = COALESCE(?, description)
                 WHERE tenant_id = ? AND folder_id = ? AND name = ?
                """, packageName, packageVersion, environment, description, tenantId, folderId, name);
    }

    /**
     * @param folderId null berarti folder bawaan — diisi oleh pemicu basis data
     *                 {@code trg_processes_folder}, bukan di sini.
     */
    public void insert(UUID tenantId, String name, String packageName, String packageVersion,
                       String environment, String description, UUID folderId) {
        database.update("""
                INSERT INTO processes
                    (id, tenant_id, name, package_name, package_version, environment, description,
                     folder_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), tenantId, name, packageName, packageVersion, environment, description,
                folderId);
    }

    /**
     * Nama pemicu proses ini di folder asal yang SUDAH ADA di folder tujuan,
     * kalau ada. Pemicu ikut pindah bersama prosesnya, dan nama pemicu unik
     * per folder.
     */
    public Optional<String> findConflictingTriggerName(UUID tenantId, String processName,
                                                       UUID sourceFolderId, UUID targetFolderId) {
        return database.queryScalar("""
                SELECT t.name FROM triggers t
                 WHERE t.tenant_id = ? AND t.folder_id = ? AND t.process_name = ?
                   AND EXISTS (SELECT 1 FROM triggers u
                                WHERE u.tenant_id = t.tenant_id AND u.folder_id = ? AND u.name = t.name)
                 ORDER BY t.name
                 LIMIT 1
                """, tenantId, sourceFolderId, processName, targetFolderId).map(String::valueOf);
    }

    /**
     * Pindahkan proses ke folder lain, BERSAMA pemicu dan riwayat pekerjaannya.
     *
     * <p>Pemicu yang tertinggal di folder lama akan menjalankan proses yang
     * sudah tidak terlihat di sana, dan riwayat yang tertinggal membuat proses
     * di folder baru tampak belum pernah dijalankan. Yang ikut hanya milik
     * folder ASAL: proses bernama sama di folder lain tidak disentuh.
     *
     * <p>Tiga perintah: pemanggilnya yang menyatukannya dalam satu transaksi.
     */
    public int moveToFolder(UUID tenantId, String name, UUID sourceFolderId, UUID targetFolderId) {
        int moved = database.update(
                "UPDATE processes SET folder_id = ? WHERE tenant_id = ? AND folder_id = ? AND name = ?",
                targetFolderId, tenantId, sourceFolderId, name);

        if (moved > 0) {
            database.update(
                    "UPDATE triggers SET folder_id = ? WHERE tenant_id = ? AND folder_id = ? AND process_name = ?",
                    targetFolderId, tenantId, sourceFolderId, name);
            database.update(
                    "UPDATE jobs SET folder_id = ? WHERE tenant_id = ? AND folder_id = ? AND process_name = ?",
                    targetFolderId, tenantId, sourceFolderId, name);
        }

        return moved;
    }

    /**
     * Dipakai penerbitan paket: menyetel paket dan versinya tanpa menyentuh
     * sisanya — di SEMUA folder tempat proses bernama itu dipasang. Studio
     * tidak tahu folder, dan "terbitkan" selama ini berarti "proses bernama
     * ini menjalankan versi yang baru".
     */
    public void linkPackage(UUID tenantId, String name, String packageName, String packageVersion) {
        database.update("""
                UPDATE processes SET package_name = ?, package_version = ?
                 WHERE tenant_id = ? AND name = ?
                """, packageName, packageVersion, tenantId, name);
    }

    public int delete(UUID tenantId, String name, UUID folderId) {
        return database.update("DELETE FROM processes WHERE tenant_id = ? AND folder_id = ? AND name = ?",
                tenantId, folderId, name);
    }
}
