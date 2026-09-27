package id.jakforge.openorchestrator.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Peran dan pola izinnya. */
@Repository
@RequiredArgsConstructor
public class RoleRepository {

    private final Database database;

    public List<Map<String, Object>> findAll(UUID tenantId) {
        return database.queryRows("""
                SELECT r.id, r.name, r.description, r.permissions, r.created_at,
                       (SELECT count(*) FROM users u
                         WHERE u.tenant_id = r.tenant_id AND u.role = r.name) AS user_count
                  FROM roles r
                 WHERE r.tenant_id = ?
                 ORDER BY r.name
                """, tenantId);
    }

    /** Peran bernama itu. Nama dicocokkan tanpa membedakan huruf besar. */
    public Optional<Map<String, Object>> findByName(UUID tenantId, String name) {
        return database.queryRow("""
                SELECT id, name, description, permissions
                  FROM roles WHERE tenant_id = ? AND lower(name) = lower(?)
                """, tenantId, name);
    }

    public void insert(UUID tenantId, String name, String description, String permissions) {
        database.update("""
                INSERT INTO roles (id, tenant_id, name, description, permissions, created_at)
                VALUES (?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), tenantId, name, description, permissions);
    }

    public int update(UUID tenantId, String currentName, String newName, String description, String permissions) {
        return database.update("""
                UPDATE roles SET name = ?, description = ?, permissions = ?
                 WHERE tenant_id = ? AND name = ?
                """, newName, description, permissions, tenantId, currentName);
    }

    public int delete(UUID tenantId, String name) {
        return database.update("DELETE FROM roles WHERE tenant_id = ? AND name = ?", tenantId, name);
    }
}
