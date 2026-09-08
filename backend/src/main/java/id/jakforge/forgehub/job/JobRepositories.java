package id.jakforge.forgehub.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepositories {

    interface Jobs extends JpaRepository<Job, UUID> {
        Page<Job> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
        List<Job> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);
        Optional<Job> findByIdAndTenantId(UUID id, UUID tenantId);
        long countByTenantIdAndStatus(UUID tenantId, String status);
    }

    interface Triggers extends JpaRepository<Trigger, UUID> {
        List<Trigger> findByTenantIdOrderByNameAsc(UUID tenantId);
        Optional<Trigger> findByIdAndTenantId(UUID id, UUID tenantId);
    }
}
