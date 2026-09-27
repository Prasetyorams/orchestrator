package id.jakforge.openorchestrator.queue;

import id.jakforge.openorchestrator.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "queues")
@Getter
@Setter
public class Queue extends TenantEntity {

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 400)
    private String description;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 2;
}
