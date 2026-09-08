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
@Table(name = "jobs")
@Getter
@Setter
public class Job extends TenantEntity {

    @Column(name = "process_id")
    private UUID processId;

    @Column(name = "robot_id")
    private UUID robotId;

    @Column(name = "machine_id")
    private UUID machineId;

    @Column(name = "job_name", nullable = false, length = 200)
    private String jobName;

    /** PENDING, RUNNING, SUCCESSFUL, FAULTED, STOPPED */
    @Column(nullable = false, length = 24)
    private String status = "PENDING";

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "error_message")
    private String errorMessage;

    /**
     * Argumen disimpan sebagai teks JSON.
     *
     * Kolomnya jsonb di PostgreSQL, tapi dipetakan ke String di sini: tipe
     * jsonb butuh pemeta khusus, dan yang dibutuhkan aplikasi ini cuma
     * menyimpan lalu meneruskannya utuh — bukan mengueri isinya.
     */
    @Column(name = "input_arguments", columnDefinition = "jsonb")
    private String inputArguments;

    @Column(name = "output_arguments", columnDefinition = "jsonb")
    private String outputArguments;
}
