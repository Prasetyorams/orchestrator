package id.jakforge.forgehub.robot;

import id.jakforge.forgehub.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "robots")
@Getter
@Setter
public class Robot extends TenantEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "machine_id")
    private UUID machineId;

    @Column(name = "environment_id")
    private UUID environmentId;

    @Column(name = "robot_type", nullable = false, length = 24)
    private String robotType = "UNATTENDED";

    @Column(nullable = false, length = 24)
    private String status = "OFFLINE";

    @Column(name = "cpu_percent", nullable = false)
    private double cpuPercent;

    @Column(name = "memory_mb", nullable = false)
    private double memoryMb;

    @Column(name = "last_heartbeat")
    private OffsetDateTime lastHeartbeat;
}
