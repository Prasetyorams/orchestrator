package id.jakforge.forgehub.robot;

import id.jakforge.forgehub.common.NotFoundException;
import id.jakforge.forgehub.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/robots")
public class RobotController {

    private final RobotRepositories.Robots robots;
    private final RobotRepositories.Machines machines;
    private final RobotRepositories.Environments environments;
    private final RobotRepositories.Credentials credentials;

    public RobotController(RobotRepositories.Robots robots,
                           RobotRepositories.Machines machines,
                           RobotRepositories.Environments environments,
                           RobotRepositories.Credentials credentials) {
        this.robots = robots;
        this.machines = machines;
        this.environments = environments;
        this.credentials = credentials;
    }

    public record RobotView(String id, String name, String robotType, String status,
                            double cpuPercent, double memoryMb,
                            String machineName, String environmentName,
                            OffsetDateTime lastHeartbeat) {
    }

    public record CreateRobot(@NotBlank String name, String robotType,
                              String machineId, String environmentId) {
    }

    /** Denyut dari JakRunner atau Studio: menyalakan status dan angka kinerjanya. */
    public record Heartbeat(String status, double cpuPercent, double memoryMb) {
    }

    @GetMapping
    public List<RobotView> list() {
        var tenantId = CurrentUser.get().tenantId();

        var machineNames = machines.findByTenantIdOrderByNameAsc(tenantId);
        var environmentNames = environments.findByTenantIdOrderByNameAsc(tenantId);

        return robots.findByTenantIdOrderByNameAsc(tenantId).stream()
                .map(r -> new RobotView(
                        r.getId().toString(),
                        r.getName(),
                        r.getRobotType(),
                        r.getStatus(),
                        r.getCpuPercent(),
                        r.getMemoryMb(),
                        machineNames.stream()
                                .filter(m -> m.getId().equals(r.getMachineId()))
                                .map(Machine::getName).findFirst().orElse(null),
                        environmentNames.stream()
                                .filter(e -> e.getId().equals(r.getEnvironmentId()))
                                .map(Environment::getName).findFirst().orElse(null),
                        r.getLastHeartbeat()))
                .toList();
    }

    @PostMapping
    public RobotView create(@RequestBody CreateRobot request) {
        var tenantId = CurrentUser.get().tenantId();

        var robot = new Robot();
        robot.setTenantId(tenantId);
        robot.setName(request.name());
        robot.setRobotType(request.robotType() == null ? "UNATTENDED" : request.robotType());
        if (request.machineId() != null) robot.setMachineId(UUID.fromString(request.machineId()));
        if (request.environmentId() != null) robot.setEnvironmentId(UUID.fromString(request.environmentId()));

        robots.save(robot);
        return list().stream().filter(v -> v.id().equals(robot.getId().toString())).findFirst().orElseThrow();
    }

    /**
     * Denyut robot.
     *
     * Robot yang belum terdaftar DIDAFTARKAN otomatis dengan namanya.
     * Menolaknya akan membuat robot baru diam-diam tidak terlihat sampai
     * ada yang membuatnya manual di layar — dan sampai saat itu, tidak ada
     * yang tahu ia sedang bekerja.
     */
    @PostMapping("/{name}/heartbeat")
    public RobotView heartbeat(@PathVariable String name, @RequestBody Heartbeat body) {
        var tenantId = CurrentUser.get().tenantId();

        var robot = robots.findByTenantIdAndNameIgnoreCase(tenantId, name).orElseGet(() -> {
            var created = new Robot();
            created.setTenantId(tenantId);
            created.setName(name);
            return created;
        });

        robot.setStatus(body.status() == null ? "AVAILABLE" : body.status());
        robot.setCpuPercent(body.cpuPercent());
        robot.setMemoryMb(body.memoryMb());
        robot.setLastHeartbeat(OffsetDateTime.now());

        robots.save(robot);

        return list().stream()
                .filter(v -> v.id().equals(robot.getId().toString()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Robot"));
    }

    @GetMapping("/machines")
    public List<Machine> machines() {
        return machines.findByTenantIdOrderByNameAsc(CurrentUser.get().tenantId());
    }

    @GetMapping("/environments")
    public List<Environment> environments() {
        return environments.findByTenantIdOrderByNameAsc(CurrentUser.get().tenantId());
    }

    /** Rahasianya TIDAK ikut dikirim — hanya nama dan penggunanya. */
    public record CredentialView(String id, String name, String username) {
    }

    @GetMapping("/credentials")
    public List<CredentialView> credentials() {
        return credentials.findByTenantIdOrderByNameAsc(CurrentUser.get().tenantId()).stream()
                .map(c -> new CredentialView(c.getId().toString(), c.getName(), c.getUsername()))
                .toList();
    }
}
