package id.jakforge.forgehub.queue;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QueueRepositories {

    interface Queues extends JpaRepository<Queue, UUID> {
        List<Queue> findByTenantIdOrderByNameAsc(UUID tenantId);
        Optional<Queue> findByIdAndTenantId(UUID id, UUID tenantId);
        long countByTenantId(UUID tenantId);
    }

    interface Items extends JpaRepository<QueueItem, UUID> {
        List<QueueItem> findByTenantIdAndQueueIdOrderByCreatedAtDesc(UUID tenantId, UUID queueId);
        long countByTenantIdAndQueueIdAndStatus(UUID tenantId, UUID queueId, String status);

        /**
         * Satu item berikutnya yang siap dikerjakan.
         *
         * Diurutkan berdasarkan prioritas lalu waktu masuk. Pengambilannya
         * dibungkus transaksi di service dan langsung menandai IN_PROGRESS,
         * supaya dua robot tidak mengerjakan item yang sama.
         */
        Optional<QueueItem> findFirstByTenantIdAndQueueIdAndStatusOrderByPriorityAscCreatedAtAsc(
                UUID tenantId, UUID queueId, String status);
    }
}
