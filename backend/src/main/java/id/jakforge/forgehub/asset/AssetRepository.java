package id.jakforge.forgehub.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {
    List<Asset> findByTenantIdOrderByNameAsc(UUID tenantId);
    Optional<Asset> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<Asset> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
    long countByTenantId(UUID tenantId);
}
