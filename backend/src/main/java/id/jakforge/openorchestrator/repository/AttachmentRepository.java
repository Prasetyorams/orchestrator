package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.model.FileContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Lampiran job dari Robot Agent — screenshot saat gagal. */
@Repository
@RequiredArgsConstructor
public class AttachmentRepository {

    private final Database database;

    public long countForJob(UUID jobId) {
        return database.count("SELECT count(*) FROM job_attachments WHERE job_id = ?", jobId);
    }

    public void insert(UUID id, UUID tenantId, UUID jobId, String kind, String fileName, String contentType,
                       byte[] content) {
        database.update("""
                INSERT INTO job_attachments (id, tenant_id, job_id, kind, file_name, content_type, size_bytes,
                                             content, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                """, id, tenantId, jobId, kind, fileName, contentType, content.length, content);
    }

    /** Daftar TANPA isinya. */
    public List<Map<String, Object>> findForJob(UUID tenantId, UUID jobId) {
        return database.queryRows("""
                SELECT id, kind, file_name, content_type, size_bytes, created_at
                  FROM job_attachments
                 WHERE tenant_id = ? AND job_id = ?
                 ORDER BY created_at
                """, tenantId, jobId);
    }

    public Optional<FileContent> findContent(UUID tenantId, UUID jobId, UUID attachmentId) {
        List<FileContent> files = database.query("""
                SELECT file_name, content_type, content FROM job_attachments
                 WHERE tenant_id = ? AND job_id = ? AND id = ?
                """, (rs, rowNumber) -> new FileContent(
                        rs.getString(1) == null ? attachmentId + ".png" : rs.getString(1),
                        rs.getString(2), rs.getBytes(3)),
                tenantId, jobId, attachmentId);

        return files.isEmpty() ? Optional.empty() : Optional.of(files.getFirst());
    }
}
