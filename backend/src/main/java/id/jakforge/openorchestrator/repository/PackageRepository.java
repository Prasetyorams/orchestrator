package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.model.PackageSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Paket automasi — diterbitkan dari Studio atau diunggah manual (V17) — beserta isinya. */
@Repository
@RequiredArgsConstructor
public class PackageRepository {

    private final Database database;

    /**
     * Daftar paket TANPA isinya.
     *
     * <p>Kolom content sengaja tidak diambil: satu paket belasan kilobita
     * dikalikan seluruh riwayat versi akan menyeret seluruh gudang tiap kali
     * halaman dibuka, dan tidak ada satu pun yang menampilkannya.
     */
    public List<Map<String, Object>> findAll(UUID tenantId, UUID folderId) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String folderFilter = "";

        // Paket tidak tinggal di folder: umpannya satu untuk seluruh penyewa,
        // seperti umpan penyewa di UiPath. Di dalam folder, yang ditampilkan
        // adalah paket yang DIPAKAI proses di folder itu.
        if (folderId != null) {
            folderFilter = " AND name IN (SELECT package_name FROM processes WHERE tenant_id = ? AND folder_id = ?)";
            args.add(tenantId);
            args.add(folderId);
        }

        return database.queryRows("""
                SELECT id, name, version, description, entry_point, published_by, published_at, size_bytes, sha256,
                       source, source_file_name, source_file_sha256
                  FROM packages
                 WHERE tenant_id = ?%s
                 ORDER BY name, published_at DESC
                """.formatted(folderFilter), args.toArray());
    }

    public boolean exists(UUID tenantId, String name, String version) {
        return database.exists("""
                SELECT count(*) FROM packages WHERE tenant_id = ? AND name = ? AND version = ?
                """, tenantId, name, version);
    }

    /**
     * Satu baris berisi metadata DAN isinya: keduanya masuk atau gagal
     * bersama, tanpa berkas yang tertinggal di luar basis data.
     *
     * @param sha256           sidik isi paket (heksa kecil), dicocokkan robot sebelum menjalankannya
     * @param sourceFileName   nama berkas yang diunggah, atau null (Terbitkan dari Studio lewat JSON)
     * @param sourceFileSha256 sidik berkas aslinya; berbeda dengan sha256 kalau isinya ditata ulang
     */
    public void insert(UUID tenantId, String name, String version, String description, String entryPoint,
                       String publishedBy, long sizeBytes, byte[] content, String sha256, PackageSource source,
                       String sourceFileName, String sourceFileSha256) {
        database.update("""
                INSERT INTO packages
                    (id, tenant_id, name, version, description, entry_point,
                     published_by, published_at, size_bytes, content, sha256,
                     source, source_file_name, source_file_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, name, version, description, entryPoint, publishedBy,
                sizeBytes, content, sha256, source.name(), sourceFileName, sourceFileSha256);
    }

    /**
     * Isi paket sebagai bita mentah.
     *
     * <p>Kueri tersendiri, bukan lewat {@link Database#queryRows}: pemetaan baris
     * sengaja mengubah BYTEA menjadi ukurannya, karena isi paket tidak pernah
     * benar dikirim sebagai bagian dari JSON.
     */
    public Optional<byte[]> findContent(UUID tenantId, String name, String version) {
        List<byte[]> contents = database.query("""
                SELECT content FROM packages WHERE tenant_id = ? AND name = ? AND version = ?
                """, (rs, rowNumber) -> rs.getBytes(1), tenantId, name, version);

        return contents.isEmpty() ? Optional.empty() : Optional.ofNullable(contents.getFirst());
    }

    /**
     * Dependensi project.json yang tersimpan (V20) dan penanda library-nya
     * (V21), sebagai teks JSON. Kosong kalau paketnya tidak ada; dependencies
     * null kalau belum pernah dibaca dari isinya.
     */
    public Optional<Map<String, Object>> findDependencies(UUID tenantId, String name, String version) {
        List<Map<String, Object>> rows = database.queryRows("""
                SELECT dependencies, libraries FROM packages WHERE tenant_id = ? AND name = ? AND version = ?
                """, tenantId, name, version);

        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void setDependencies(UUID tenantId, String name, String version, String dependenciesJson,
                                String librariesJson) {
        database.update("""
                UPDATE packages SET dependencies = ?, libraries = ? WHERE tenant_id = ? AND name = ? AND version = ?
                """, dependenciesJson, librariesJson, tenantId, name, version);
    }

    /** Versi paket yang sedang disematkan proses mana pun, sekali per versi. */
    public List<Map<String, Object>> findPinnedVersions(UUID tenantId) {
        return database.queryRows("""
                SELECT DISTINCT k.name, k.version, k.dependencies, k.libraries
                  FROM processes p
                  JOIN packages k ON k.tenant_id = p.tenant_id AND k.name = p.package_name AND k.version = p.package_version
                 WHERE p.tenant_id = ?
                """, tenantId);
    }

    /** Proses yang menyematkan versi ini, sebagai "folder / proses". */
    public List<String> findProcessesUsing(UUID tenantId, String name, String version) {
        return database.query("""
                SELECT COALESCE(f.name, '?') || ' / ' || p.name
                  FROM processes p
                  LEFT JOIN folders f ON f.id = p.folder_id
                 WHERE p.tenant_id = ? AND p.package_name = ? AND p.package_version = ?
                 ORDER BY 1
                """, (rs, rowNumber) -> rs.getString(1), tenantId, name, version);
    }

    /**
     * Job yang sedang dipegang robot dengan versi ini. Job yang masih
     * PENDING belum memilih versi: ia memakai versi prosesnya saat diambil,
     * dan itu sudah dijaga {@link #findProcessesUsing}.
     */
    public int countHeldJobsUsing(UUID tenantId, String name, String version) {
        return database.queryScalar("""
                SELECT count(*) FROM jobs
                 WHERE tenant_id = ? AND package_name = ? AND package_version = ? AND state IN %s
                """.formatted(JobRepository.HELD_STATES), tenantId, name, version)
                .map(n -> ((Number) n).intValue()).orElse(0);
    }

    public int delete(UUID tenantId, String name, String version) {
        return database.update("DELETE FROM packages WHERE tenant_id = ? AND name = ? AND version = ?",
                tenantId, name, version);
    }
}
