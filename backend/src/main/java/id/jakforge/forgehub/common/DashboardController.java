package id.jakforge.forgehub.common;

import id.jakforge.forgehub.asset.AssetRepository;
import id.jakforge.forgehub.job.JobRepositories;
import id.jakforge.forgehub.log.LogRepository;
import id.jakforge.forgehub.process.ProcessRepositories;
import id.jakforge.forgehub.queue.QueueRepositories;
import id.jakforge.forgehub.robot.RobotRepositories;
import id.jakforge.forgehub.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Angka-angka untuk kartu ringkasan di halaman Dashboard.
 *
 * Semuanya dihitung dengan COUNT, bukan dengan mengambil daftarnya lalu
 * menghitung panjangnya. Bedanya tidak terasa pada sepuluh baris, tapi
 * pada tabel log yang berisi jutaan baris, mengambil semuanya untuk
 * menghitung satu angka adalah cara memastikan halaman ini tidak pernah
 * selesai dimuat.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final RobotRepositories.Robots robots;
    private final JobRepositories.Jobs jobs;
    private final AssetRepository assets;
    private final QueueRepositories.Queues queues;
    private final ProcessRepositories.Processes processes;
    private final LogRepository logs;

    public DashboardController(RobotRepositories.Robots robots,
                               JobRepositories.Jobs jobs,
                               AssetRepository assets,
                               QueueRepositories.Queues queues,
                               ProcessRepositories.Processes processes,
                               LogRepository logs) {
        this.robots = robots;
        this.jobs = jobs;
        this.assets = assets;
        this.queues = queues;
        this.processes = processes;
        this.logs = logs;
    }

    public record Summary(
            long totalRobots,
            long availableRobots,
            long runningJobs,
            long pendingJobs,
            long faultedJobs,
            long totalAssets,
            long totalQueues,
            long totalProcesses,
            long alerts) {
    }

    @GetMapping("/summary")
    public Summary summary() {
        var tenantId = CurrentUser.get().tenantId();

        return new Summary(
                robots.countByTenantId(tenantId),
                robots.countByTenantIdAndStatus(tenantId, "AVAILABLE"),
                jobs.countByTenantIdAndStatus(tenantId, "RUNNING"),
                jobs.countByTenantIdAndStatus(tenantId, "PENDING"),
                jobs.countByTenantIdAndStatus(tenantId, "FAULTED"),
                assets.countByTenantId(tenantId),
                queues.countByTenantId(tenantId),
                processes.countByTenantId(tenantId),
                logs.countByTenantIdAndLevel(tenantId, "ERROR"));
    }
}
