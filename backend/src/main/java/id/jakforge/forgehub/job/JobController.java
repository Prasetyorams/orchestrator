package id.jakforge.forgehub.job;

import id.jakforge.forgehub.common.NotFoundException;
import id.jakforge.forgehub.process.ProcessRepositories;
import id.jakforge.forgehub.robot.RobotRepositories;
import id.jakforge.forgehub.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobRepositories.Jobs jobs;
    private final JobRepositories.Triggers triggers;
    private final ProcessRepositories.Processes processes;
    private final RobotRepositories.Robots robots;
    private final RobotRepositories.Machines machines;

    public JobController(JobRepositories.Jobs jobs,
                         JobRepositories.Triggers triggers,
                         ProcessRepositories.Processes processes,
                         RobotRepositories.Robots robots,
                         RobotRepositories.Machines machines) {
        this.jobs = jobs;
        this.triggers = triggers;
        this.processes = processes;
        this.robots = robots;
        this.machines = machines;
    }

    public record JobView(String id, String jobName, String status,
                          String robotName, String machineName,
                          OffsetDateTime startedAt, OffsetDateTime finishedAt,
                          String duration, String errorMessage) {
    }

    public record StartJob(@NotBlank String processId, String robotId, String inputArguments) {
    }

    public record UpdateJob(String status, String errorMessage, String outputArguments) {
    }

    @GetMapping
    public List<JobView> list(@RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "50") int size,
                              @RequestParam(required = false) String status) {

        var tenantId = CurrentUser.get().tenantId();

        var found = (status == null || status.isBlank())
                ? jobs.findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(page, size)).getContent()
                : jobs.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status);

        var allRobots = robots.findByTenantIdOrderByNameAsc(tenantId);
        var allMachines = machines.findByTenantIdOrderByNameAsc(tenantId);

        return found.stream().map(j -> new JobView(
                j.getId().toString(),
                j.getJobName(),
                j.getStatus(),
                allRobots.stream().filter(r -> r.getId().equals(j.getRobotId()))
                        .map(r -> r.getName()).findFirst().orElse("-"),
                allMachines.stream().filter(m -> m.getId().equals(j.getMachineId()))
                        .map(m -> m.getName()).findFirst().orElse("-"),
                j.getStartedAt(),
                j.getFinishedAt(),
                describeDuration(j),
                j.getErrorMessage())).toList();
    }

    @PostMapping
    public JobView start(@RequestBody StartJob request) {
        var tenantId = CurrentUser.get().tenantId();

        var process = processes.findByIdAndTenantId(UUID.fromString(request.processId()), tenantId)
                .orElseThrow(() -> new NotFoundException("Proses"));

        var job = new Job();
        job.setTenantId(tenantId);
        job.setProcessId(process.getId());
        job.setJobName(process.getName());
        job.setStatus("PENDING");
        job.setInputArguments(request.inputArguments());

        if (request.robotId() != null && !request.robotId().isBlank()) {
            var robot = robots.findByIdAndTenantId(UUID.fromString(request.robotId()), tenantId)
                    .orElseThrow(() -> new NotFoundException("Robot"));
            job.setRobotId(robot.getId());
            job.setMachineId(robot.getMachineId());
        }

        jobs.save(job);

        return list(0, 1, null).stream()
                .filter(v -> v.id().equals(job.getId().toString()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Job"));
    }

    /** Dipanggil robot saat keadaan pekerjaannya berubah. */
    @PatchMapping("/{id}")
    public JobView update(@PathVariable String id, @RequestBody UpdateJob request) {
        var tenantId = CurrentUser.get().tenantId();

        var job = jobs.findByIdAndTenantId(UUID.fromString(id), tenantId)
                .orElseThrow(() -> new NotFoundException("Job"));

        if (request.status() != null) {
            job.setStatus(request.status());

            if ("RUNNING".equals(request.status()) && job.getStartedAt() == null) {
                job.setStartedAt(OffsetDateTime.now());
            }

            if (List.of("SUCCESSFUL", "FAULTED", "STOPPED").contains(request.status())) {
                job.setFinishedAt(OffsetDateTime.now());
            }
        }

        if (request.errorMessage() != null) job.setErrorMessage(request.errorMessage());
        if (request.outputArguments() != null) job.setOutputArguments(request.outputArguments());

        jobs.save(job);

        return list(0, 200, null).stream()
                .filter(v -> v.id().equals(job.getId().toString()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Job"));
    }

    @GetMapping("/triggers")
    public List<Trigger> triggers() {
        return triggers.findByTenantIdOrderByNameAsc(CurrentUser.get().tenantId());
    }

    /**
     * Lama berjalan dalam bentuk yang enak dibaca.
     *
     * Pekerjaan yang masih berjalan dihitung sampai SEKARANG, bukan
     * dikosongkan: yang paling ingin dilihat orang di tabel justru sudah
     * berapa lama sebuah pekerjaan menggantung.
     */
    private static String describeDuration(Job job) {
        if (job.getStartedAt() == null) return "-";

        var end = job.getFinishedAt() == null ? OffsetDateTime.now() : job.getFinishedAt();
        var seconds = Duration.between(job.getStartedAt(), end).getSeconds();

        if (seconds < 60) return seconds + " dtk";
        if (seconds < 3600) return (seconds / 60) + " mnt " + (seconds % 60) + " dtk";
        return (seconds / 3600) + " jam " + ((seconds % 3600) / 60) + " mnt";
    }
}
