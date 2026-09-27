package id.jakforge.forgehub.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lingkungan (Production, Development, ...) tempat robot dikelompokkan. */
@Repository
@RequiredArgsConstructor
public class EnvironmentRepository {

    private final Database database;

    public List<Map<String, Object>> findAll(UUID tenantId) {
        return database.queryRows("""
                SELECT e.id, e.name, e.description, e.created_at,
                       (SELECT count(*) FROM robots r
                         WHERE r.tenant_id = e.tenant_id AND r.environment = e.name) AS robot_count
                  FROM environments e
                 WHERE e.tenant_id = ?
                 ORDER BY e.name
                """, tenantId);
    }

    public boolean existsByName(UUID tenantId, String name) {
        return database.exists("SELECT count(*) FROM environments WHERE tenant_id = ? AND name = ?",
                tenantId, name);
    }

    public void insert(UUID tenantId, String name, String description) {
        database.update("""
                INSERT INTO environments (id, tenant_id, name, description, created_at)
                VALUES (?, ?, ?, ?, now())
                """, UUID.randomUUID(), tenantId, name, description);
    }

    public int deleteByName(UUID tenantId, String name) {
        return database.update("DELETE FROM environments WHERE tenant_id = ? AND name = ?", tenantId, name);
    }
}
