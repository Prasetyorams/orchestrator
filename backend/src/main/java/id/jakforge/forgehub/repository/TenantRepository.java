package id.jakforge.forgehub.repository;

import id.jakforge.forgehub.common.Uuids;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Penyewa. */
@Repository
@RequiredArgsConstructor
public class TenantRepository {

    private final Database database;

    public List<Map<String, Object>> findAll() {
        return database.queryRows("""
                SELECT t.id, t.name, t.display_name, t.created_at,
                       (SELECT count(*) FROM users u WHERE u.tenant_id = t.id)  AS user_count,
                       (SELECT count(*) FROM robots r WHERE r.tenant_id = t.id) AS robot_count
                  FROM tenants t
                 ORDER BY t.name
                """);
    }

    public Optional<UUID> findIdByName(String name) {
        return database.queryScalar("SELECT id FROM tenants WHERE name = ?", name).map(Uuids::fromColumn);
    }

    public Optional<String> findName(UUID tenantId) {
        return database.queryScalar("SELECT name FROM tenants WHERE id = ?", tenantId).map(String::valueOf);
    }
}
