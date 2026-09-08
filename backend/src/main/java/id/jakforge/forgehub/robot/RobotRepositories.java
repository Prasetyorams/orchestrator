package id.jakforge.forgehub.robot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RobotRepositories {

    interface Robots extends JpaRepository<Robot, UUID> {
        List<Robot> findByTenantIdOrderByNameAsc(UUID tenantId);
        Optional<Robot> findByIdAndTenantId(UUID id, UUID tenantId);
        Optional<Robot> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
        long countByTenantId(UUID tenantId);
        long countByTenantIdAndStatus(UUID tenantId, String status);
    }

    interface Machines extends JpaRepository<Machine, UUID> {
        List<Machine> findByTenantIdOrderByNameAsc(UUID tenantId);
        Optional<Machine> findByIdAndTenantId(UUID id, UUID tenantId);
    }

    interface Environments extends JpaRepository<Environment, UUID> {
        List<Environment> findByTenantIdOrderByNameAsc(UUID tenantId);
    }

    interface Credentials extends JpaRepository<Credential, UUID> {
        List<Credential> findByTenantIdOrderByNameAsc(UUID tenantId);
    }
}
