package id.jakforge.forgehub.process;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessRepositories {

    interface Processes extends JpaRepository<AutomationProcess, UUID> {
        List<AutomationProcess> findByTenantIdOrderByNameAsc(UUID tenantId);
        Optional<AutomationProcess> findByIdAndTenantId(UUID id, UUID tenantId);
        long countByTenantId(UUID tenantId);
    }

    interface Packages extends JpaRepository<AutomationPackage, UUID> {
        List<AutomationPackage> findByTenantIdOrderByNameAscVersionDesc(UUID tenantId);
    }
}
