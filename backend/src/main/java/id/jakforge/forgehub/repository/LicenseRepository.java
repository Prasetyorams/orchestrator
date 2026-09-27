package id.jakforge.forgehub.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lisensi robot per produk. */
@Repository
@RequiredArgsConstructor
public class LicenseRepository {

    private final Database database;

    public List<Map<String, Object>> findAll(UUID tenantId) {
        return database.queryRows("""
                SELECT id, product, total, used, expires_at
                  FROM licenses WHERE tenant_id = ? ORDER BY product
                """, tenantId);
    }
}
