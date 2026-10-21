package id.jakforge.openorchestrator.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Feed library (V20): library OpenStudio beserta isinya, dan library yang
 * diberikan ke tiap job saat diambil robot.
 *
 * <p>Id library (kolom name) tidak peka huruf besar-kecil, seperti Id NuGet;
 * versinya dicocokkan persis seperti yang tersimpan.
 */
@Repository
@RequiredArgsConstructor
public class LibraryRepository {

    /** Bentuk baris untuk daftar dan pencocokan — tanpa isi. */
    private static final String COLUMNS = """
            name, version, description, authors, assembly_file, dependencies, published_by, published_at,
            size_bytes, sha256, source_file_name
            """;

    private final Database database;

    /** Daftar TANPA isinya; {@code search} mencocokkan Id dan keterangan. */
    public List<Map<String, Object>> findAll(UUID tenantId, String search) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String filter = "";

        if (search != null && !search.isBlank()) {
            filter = " AND (name ILIKE ? ESCAPE '\\' OR description ILIKE ? ESCAPE '\\')";
            String pattern = "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(pattern);
            args.add(pattern);
        }

        return database.queryRows("SELECT " + COLUMNS + " FROM libraries WHERE tenant_id = ?" + filter
                + " ORDER BY lower(name), published_at DESC", args.toArray());
    }

    /** Semua versi satu library (Id tanpa peduli huruf besar-kecil). */
    public List<Map<String, Object>> findVersions(UUID tenantId, String name) {
        return database.queryRows("SELECT " + COLUMNS + " FROM libraries WHERE tenant_id = ? AND lower(name) = lower(?)",
                tenantId, name);
    }

    /** Id library yang ada di feed, huruf kecil. */
    public List<String> findNames(UUID tenantId) {
        return database.query("SELECT DISTINCT lower(name) FROM libraries WHERE tenant_id = ?",
                (rs, rowNumber) -> rs.getString(1), tenantId);
    }

    public void insert(UUID tenantId, String name, String version, String description, String authors,
                       String assemblyFile, String dependenciesJson, String publishedBy, byte[] content,
                       String sha256, String sourceFileName) {
        database.update("""
                INSERT INTO libraries
                    (id, tenant_id, name, version, description, authors, assembly_file, dependencies,
                     published_by, published_at, size_bytes, content, sha256, source_file_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, name, version, description, authors, assemblyFile,
                dependenciesJson, publishedBy, content.length, content, sha256, sourceFileName);
    }

    /** Id sebagaimana tersimpan, dan isi .nupkg-nya. */
    public record Content(String name, String version, byte[] bytes) {
    }

    public Optional<Content> findContent(UUID tenantId, String name, String version) {
        List<Content> rows = database.query("""
                SELECT name, version, content FROM libraries
                 WHERE tenant_id = ? AND lower(name) = lower(?) AND version = ?
                """, (rs, rowNumber) -> new Content(rs.getString(1), rs.getString(2), rs.getBytes(3)),
                tenantId, name, version);

        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public int delete(UUID tenantId, String name, String version) {
        return database.update("DELETE FROM libraries WHERE tenant_id = ? AND lower(name) = lower(?) AND version = ?",
                tenantId, name, version);
    }

    // ----------------------------------------------------------------- per job

    /** Library yang diberikan ke job saat diambil: yang boleh diunduh robotnya selama job itu dipegang. */
    public void recordForJob(UUID tenantId, UUID jobId, String name, String version, String sha256) {
        database.update("""
                INSERT INTO job_libraries (job_id, tenant_id, name, version, sha256) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (job_id, name) DO UPDATE SET version = EXCLUDED.version, sha256 = EXCLUDED.sha256
                """, jobId, tenantId, name, version, sha256);
    }

    /** Job robot di mesin ini yang sedang dipegang dan diberi library versi ini. */
    public boolean machineRunsLibrary(UUID machineId, String name, String version) {
        return database.exists("""
                SELECT count(*) FROM job_libraries l
                  JOIN jobs j ON j.id = l.job_id
                 WHERE j.machine_id = ? AND j.contract_version = 2 AND j.state IN %s
                   AND lower(l.name) = lower(?) AND l.version = ?
                """.formatted(JobRepository.HELD_STATES), machineId, name, version);
    }

    /** Job yang sedang dipegang robot dengan library versi ini. */
    public int countHeldJobsUsing(UUID tenantId, String name, String version) {
        return database.queryScalar("""
                SELECT count(*) FROM job_libraries l
                  JOIN jobs j ON j.id = l.job_id
                 WHERE l.tenant_id = ? AND lower(l.name) = lower(?) AND l.version = ? AND j.state IN %s
                """.formatted(JobRepository.HELD_STATES), tenantId, name, version)
                .map(n -> ((Number) n).intValue()).orElse(0);
    }
}
