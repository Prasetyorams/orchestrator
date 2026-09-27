package id.jakforge.forgehub.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mesin tempat robot berjalan. */
@Repository
@RequiredArgsConstructor
public class MachineRepository {

    private final Database database;

    public List<Map<String, Object>> findAll(UUID tenantId) {
        return database.queryRows("""
                SELECT m.id, m.name, m.type, m.license_key, m.description, m.created_at,
                       (SELECT count(*) FROM robots r
                         WHERE r.tenant_id = m.tenant_id AND r.machine_name = m.name) AS robot_count
                  FROM machines m
                 WHERE m.tenant_id = ?
                 ORDER BY m.name
                """, tenantId);
    }

    public boolean existsByName(UUID tenantId, String name) {
        return database.exists("SELECT count(*) FROM machines WHERE tenant_id = ? AND name = ?", tenantId, name);
    }

    public void insert(UUID tenantId, String name, String type, String licenseKey, String description) {
        database.update("""
                INSERT INTO machines (id, tenant_id, name, type, license_key, description, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), tenantId, name, type, licenseKey, description);
    }

    public int deleteByName(UUID tenantId, String name) {
        return database.update("DELETE FROM machines WHERE tenant_id = ? AND name = ?", tenantId, name);
    }
}
