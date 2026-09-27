package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.model.FileContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Ember penyimpanan (gudang berkas) dan berkas di dalamnya. */
@Repository
@RequiredArgsConstructor
public class BucketRepository {

    private final Database database;

    /** @param folderId null berarti seluruh penyewa. */
    public List<Map<String, Object>> findAll(UUID tenantId, UUID folderId) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String folderFilter = "";

        if (folderId != null) {
            folderFilter = " AND b.folder_id = ?";
            args.add(folderId);
        }

        return database.queryRows("""
                SELECT b.id, b.name, b.description, b.created_at, b.folder_id,
                       count(f.id)                    AS file_count,
                       COALESCE(sum(f.size_bytes), 0) AS total_bytes
                  FROM buckets b
                  LEFT JOIN bucket_files f
                         ON f.tenant_id = b.tenant_id AND f.bucket_name = b.name
                 WHERE b.tenant_id = ?%s
                 GROUP BY b.id, b.name, b.description, b.created_at, b.folder_id
                 ORDER BY b.name
                """.formatted(folderFilter), args.toArray());
    }

    public boolean existsByName(UUID tenantId, String name) {
        return database.exists("SELECT count(*) FROM buckets WHERE tenant_id = ? AND name = ?", tenantId, name);
    }

    /** @param folderId null berarti folder bawaan (diisi pemicu basis data). */
    public void insert(UUID tenantId, String name, String description, UUID folderId) {
        database.update("""
                INSERT INTO buckets (id, tenant_id, name, description, folder_id, created_at)
                VALUES (?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), tenantId, name, description, folderId);
    }

    public int moveToFolder(UUID tenantId, String name, UUID folderId) {
        return database.update("UPDATE buckets SET folder_id = ? WHERE tenant_id = ? AND name = ?",
                folderId, tenantId, name);
    }

    /**
     * Berkasnya ikut dibuang: berkas tanpa gudang tidak bisa dicapai lewat
     * jalan mana pun. Dua perintah: pemanggilnya yang menyatukannya dalam satu
     * transaksi.
     */
    public int deleteWithFiles(UUID tenantId, String name) {
        database.update("DELETE FROM bucket_files WHERE tenant_id = ? AND bucket_name = ?", tenantId, name);

        return database.update("DELETE FROM buckets WHERE tenant_id = ? AND name = ?", tenantId, name);
    }

    public List<Map<String, Object>> findFiles(UUID tenantId, String bucketName) {
        return database.queryRows("""
                SELECT id, file_name, content_type, size_bytes, uploaded_by, uploaded_at
                  FROM bucket_files
                 WHERE tenant_id = ? AND bucket_name = ?
                 ORDER BY uploaded_at DESC
                """, tenantId, bucketName);
    }

    /** Berkas bernama sama dibuang lebih dulu: diganti, bukan ditumpuk. */
    public void deleteFileByName(UUID tenantId, String bucketName, String fileName) {
        database.update("DELETE FROM bucket_files WHERE tenant_id = ? AND bucket_name = ? AND file_name = ?",
                tenantId, bucketName, fileName);
    }

    public void insertFile(UUID tenantId, String bucketName, String fileName, String contentType,
                           long sizeBytes, String uploadedBy, byte[] content) {
        database.update("""
                INSERT INTO bucket_files
                    (id, tenant_id, bucket_name, file_name, content_type, size_bytes,
                     uploaded_by, uploaded_at, content)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?)
                """, UUID.randomUUID(), tenantId, bucketName, fileName, contentType, sizeBytes, uploadedBy, content);
    }

    /** Isi berkas: kueri tersendiri, dengan alasan yang sama seperti isi paket. */
    public Optional<FileContent> findFileContent(UUID tenantId, String bucketName, UUID fileId) {
        return database.query("""
                SELECT file_name, content_type, content FROM bucket_files
                 WHERE tenant_id = ? AND bucket_name = ? AND id = ?
                """, (rs, rowNumber) -> new FileContent(
                        rs.getString(1),
                        rs.getString(2) == null ? FileContent.DEFAULT_CONTENT_TYPE : rs.getString(2),
                        rs.getBytes(3) == null ? new byte[0] : rs.getBytes(3)),
                tenantId, bucketName, fileId).stream().findFirst();
    }

    public int deleteFile(UUID tenantId, String bucketName, UUID fileId) {
        return database.update("DELETE FROM bucket_files WHERE tenant_id = ? AND bucket_name = ? AND id = ?",
                tenantId, bucketName, fileId);
    }
}
