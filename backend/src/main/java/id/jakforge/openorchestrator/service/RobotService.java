package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Strings;
import id.jakforge.openorchestrator.common.Timestamps;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.dto.request.CreateRobotRequest;
import id.jakforge.openorchestrator.dto.request.HeartbeatRequest;
import id.jakforge.openorchestrator.dto.request.UpdateRobotRequest;
import id.jakforge.openorchestrator.dto.response.HeartbeatResponse;
import id.jakforge.openorchestrator.model.MachineTypes;
import id.jakforge.openorchestrator.model.RobotStatus;
import id.jakforge.openorchestrator.model.Severity;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.repository.RobotRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.SecretBox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Aturan tentang robot: pendaftaran, denyut, setelan unattended, dan penghapusan. */
@Service
@RequiredArgsConstructor
public class RobotService {

    private static final String ALERT_SOURCE = "robots";
    private static final String AUTO_REGISTERED_MACHINE_DESCRIPTION = "Terdaftar sendiri lewat denyut robot.";
    private static final String ATTENDED = "Attended";

    private final RobotRepository robotRepository;
    private final MachineRepository machineRepository;
    private final JobRepository jobRepository;
    private final AlertRepository alertRepository;
    private final FolderAccessService folderAccessService;
    private final SecretBox secretBox;

    /** Dengan {@code folderId}: hanya robot yang ditugaskan ke folder itu. */
    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal, String folderId) {
        return robotRepository.findAll(principal.tenantId(),
                folderAccessService.resolveFolderFilter(principal, folderId));
    }

    public Map<String, Object> findByName(OpenOrchestratorPrincipal principal, String name) {
        return robotRepository.findByName(principal.tenantId(), name).orElseThrow(() -> robotNotFound(name));
    }

    /**
     * Denyut dari robot v1.
     *
     * <p>Robot yang belum dikenal MENDAFTARKAN DIRINYA di sini, bukan ditolak.
     * Memasang JakRunner di mesin baru lalu harus membuka dasbor untuk
     * mendaftarkannya lebih dulu adalah langkah yang selalu terlupakan, dan
     * gejalanya — robot menyala tapi tidak muncul di mana pun — tidak
     * mengarahkan siapa pun ke langkah yang terlupa itu.
     *
     * <p>Jawabannya membawa perintah StopJob untuk job robot ini yang diminta
     * berhenti ({@code heartbeat.commands}): tanpa itu, tombol Stop di dasbor
     * tidak pernah sampai ke robot.
     */
    @Transactional
    public HeartbeatResponse recordHeartbeat(OpenOrchestratorPrincipal principal, String name, HeartbeatRequest heartbeat) {
        UUID tenantId = principal.tenantId();
        String status = RobotStatus.fromHeartbeat(heartbeat.status()).name();

        if (robotRepository.existsByName(tenantId, name)) {
            robotRepository.recordHeartbeat(tenantId, name, heartbeat.machineName(),
                    status, heartbeat.cpuPercent(), heartbeat.memoryMb());
        } else {
            robotRepository.registerFromHeartbeat(tenantId, name, heartbeat.machineName(),
                    status, heartbeat.cpuPercent(), heartbeat.memoryMb());

            ensureMachineRegistered(tenantId, heartbeat.machineName());

            alertRepository.insert(tenantId, Severity.Info, "Robot baru terdaftar",
                    "Robot '" + name + "' menyambung untuk pertama kali.", ALERT_SOURCE);
        }

        List<Map<String, Object>> commands = jobRepository.findStopRequestsForV1Robot(tenantId, name).stream()
                .map(job -> {
                    Map<String, Object> command = new LinkedHashMap<>();
                    command.put("type", "StopJob");
                    command.put("jobId", job.get("id"));
                    return command;
                })
                .toList();

        return new HeartbeatResponse(true, Timestamps.nowText(), commands);
    }

    @Transactional
    public void create(OpenOrchestratorPrincipal principal, CreateRobotRequest request) {
        UUID tenantId = principal.tenantId();

        if (robotRepository.existsByName(tenantId, request.name())) {
            throw ApiException.conflict("Robot '" + request.name() + "' sudah ada.");
        }

        ensureMachineRegistered(tenantId, request.machineName());

        UUID machineId = unattendedMachineId(tenantId, request.type(), request.machineName());
        boolean passwordLocal = Boolean.TRUE.equals(request.windowsPasswordLocal());
        String password = passwordLocal || request.windowsPassword() == null ? null
                : secretBox.protect(request.windowsPassword());

        robotRepository.insert(tenantId, request.name(), request.machineName(), request.username(),
                request.type(), request.environment(), request.description(), machineId,
                request.windowsUsername(), password, passwordLocal, request.sessionPolicy());

        if (machineId != null) machineRepository.bumpSettingsVersion(machineId);
    }

    /**
     * Ubah setelan robot, termasuk akun Windows-nya.
     *
     * <p>Mesin lama DAN mesin baru sama-sama dinaikkan versi setelannya: agent
     * di mesin lama harus berhenti melayani robot ini, dan agent di mesin baru
     * harus mulai.
     */
    @Transactional
    public void update(OpenOrchestratorPrincipal principal, String name, UpdateRobotRequest request) {
        UUID tenantId = principal.tenantId();
        Map<String, Object> current = robotRepository.findForUpdate(tenantId, name).orElseThrow(() -> robotNotFound(name));

        UUID robotId = Uuids.fromColumn(current.get("id"));
        UUID oldMachineId = Uuids.fromColumn(current.get("machineId"));

        String type = request.type() != null ? request.type() : (String) current.get("type");
        String environment = request.environment() != null ? request.environment() : (String) current.get("environment");
        String description = request.description() != null ? Strings.emptyToNull(request.description())
                : (String) current.get("description");

        // machineName: tidak dikirim = tetap; kosong = lepas dari mesin.
        String machineName = request.machineName() == null ? (String) current.get("machineName")
                : Strings.trimToNull(request.machineName());

        ensureMachineRegistered(tenantId, machineName);
        UUID machineId = unattendedMachineId(tenantId, type, machineName);

        String windowsUsername = request.windowsUsername() != null ? Strings.trimToNull(request.windowsUsername())
                : (String) current.get("windowsUsername");
        boolean passwordLocal = request.windowsPasswordLocal() != null ? request.windowsPasswordLocal()
                : Boolean.TRUE.equals(current.get("windowsPasswordLocal"));
        String sessionPolicy = request.sessionPolicy() != null ? request.sessionPolicy()
                : (String) current.get("sessionPolicy");

        String password = passwordLocal || request.windowsPassword() == null ? null
                : secretBox.protect(request.windowsPassword());

        robotRepository.updateConfig(tenantId, robotId, type, environment, description, machineId, machineName,
                windowsUsername, password, passwordLocal, passwordLocal, sessionPolicy);

        if (oldMachineId != null) machineRepository.bumpSettingsVersion(oldMachineId);
        if (machineId != null && !Objects.equals(machineId, oldMachineId)) machineRepository.bumpSettingsVersion(machineId);
    }

    public void delete(OpenOrchestratorPrincipal principal, String name) {
        List<UUID> machines = robotRepository.findMachineIdsByName(principal.tenantId(), name);

        if (robotRepository.deleteByName(principal.tenantId(), name) == 0) throw robotNotFound(name);

        machines.forEach(machineRepository::bumpSettingsVersion);
    }

    /**
     * Robot selain Attended yang menyebut mesin diikat ke mesin itu — itulah
     * robot yang dilayani Robot Agent-nya. Robot Attended tidak pernah diikat:
     * ia mewakili orang yang sedang login, bukan akun Windows yang disiapkan agent.
     */
    private UUID unattendedMachineId(UUID tenantId, String type, String machineName) {
        if (machineName == null || ATTENDED.equalsIgnoreCase(type)) return null;

        return machineRepository.findByName(tenantId, machineName)
                .map(machine -> Uuids.fromColumn(machine.get("id")))
                .orElse(null);
    }

    /**
     * Mesin yang disebut robot didaftarkan kalau belum ada.
     *
     * <p>Tanpa ini, halaman Machines kosong sementara halaman Robots penuh —
     * dan keduanya benar menurut datanya masing-masing, yang justru membuat
     * kejanggalannya sulit dijelaskan.
     */
    private void ensureMachineRegistered(UUID tenantId, String machineName) {
        if (machineName == null || machineName.isBlank()) return;
        if (machineRepository.existsByName(tenantId, machineName)) return;

        machineRepository.insert(tenantId, machineName, MachineTypes.STANDARD, null,
                AUTO_REGISTERED_MACHINE_DESCRIPTION);
    }

    private static ApiException robotNotFound(String name) {
        return ApiException.notFound("Robot '" + name + "' tidak ada.");
    }
}
