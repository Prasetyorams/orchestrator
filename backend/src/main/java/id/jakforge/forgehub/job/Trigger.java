package id.jakforge.forgehub.job;

import id.jakforge.forgehub.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "triggers")
@Getter
@Setter
public class Trigger extends TenantEntity {

    @Column(name = "process_id")
    private UUID processId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "cron_expression", length = 120)
    private String cronExpression;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "next_run_at")
    private OffsetDateTime nextRunAt;
}
