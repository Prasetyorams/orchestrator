package id.jakforge.forgehub.queue;

import id.jakforge.forgehub.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "queue_items")
@Getter
@Setter
public class QueueItem extends TenantEntity {

    @Column(name = "queue_id", nullable = false)
    private UUID queueId;

    @Column(length = 200)
    private String reference;

    /** NEW, IN_PROGRESS, SUCCESSFUL, FAILED, RETRIED */
    @Column(nullable = false, length = 24)
    private String status = "NEW";

    @Column(nullable = false, length = 16)
    private String priority = "NORMAL";

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;
}
