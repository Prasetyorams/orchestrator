package id.jakforge.forgehub.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Paket automasi yang diterbitkan Studio, beserta isinya. */
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
                SELECT id, name, version, description, entry_point, published_by, published_at, size_bytes
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
     * COALESCE pada content: penerbitan ulang yang hanya memperbarui keterangan
     * tidak boleh MENGHAPUS isi paket yang sudah ada.
     */
    public void update(UUID tenantId, String name, String version, String description,
                       String entryPoint, String publishedBy, long sizeBytes, byte[] content) {
        database.update("""
                UPDATE packages
                   SET description = ?, entry_point = ?, published_by = ?,
                       published_at = now(), size_bytes = ?,
                       content = COALESCE(?, content)
                 WHERE tenant_id = ? AND name = ? AND version = ?
                """, description, entryPoint, publishedBy, sizeBytes, content, tenantId, name, version);
    }

    public void insert(UUID tenantId, String name, String version, String description,
                       String entryPoint, String publishedBy, long sizeBytes, byte[] content) {
        database.update("""
                INSERT INTO packages
                    (id, tenant_id, name, version, description, entry_point,
                     published_by, published_at, size_bytes, content)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, ?)
                """, UUID.randomUUID(), tenantId, name, version, description, entryPoint, publishedBy,
                sizeBytes, content);
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

    public int delete(UUID tenantId, String name, String version) {
        return database.update("DELETE FROM packages WHERE tenant_id = ? AND name = ? AND version = ?",
                tenantId, name, version);
    }
}
