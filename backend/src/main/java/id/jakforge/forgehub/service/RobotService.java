package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.common.Timestamps;
import id.jakforge.forgehub.dto.request.CreateRobotRequest;
import id.jakforge.forgehub.dto.request.HeartbeatRequest;
import id.jakforge.forgehub.dto.response.HeartbeatResponse;
import id.jakforge.forgehub.model.MachineTypes;
import id.jakforge.forgehub.model.RobotStatus;
import id.jakforge.forgehub.model.Severity;
import id.jakforge.forgehub.repository.AlertRepository;
import id.jakforge.forgehub.repository.MachineRepository;
import id.jakforge.forgehub.repository.RobotRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Aturan tentang robot: pendaftaran, denyut, dan penghapusan. */
@Service
@RequiredArgsConstructor
public class RobotService {

    private static final String ALERT_SOURCE = "robots";
    private static final String AUTO_REGISTERED_MACHINE_DESCRIPTION = "Terdaftar sendiri lewat denyut robot.";

    private final RobotRepository robotRepository;
    private final MachineRepository machineRepository;
    private final AlertRepository alertRepository;
    private final FolderAccessService folderAccessService;

    /** Dengan {@code folderId}: hanya robot yang ditugaskan ke folder itu. */
    public List<Map<String, Object>> findAll(ForgeHubPrincipal principal, String folderId) {
        return robotRepository.findAll(principal.tenantId(),
                folderAccessService.resolveFolderFilter(principal, folderId));
    }

    public Map<String, Object> findByName(ForgeHubPrincipal principal, String name) {
        return robotRepository.findByName(principal.tenantId(), name).orElseThrow(() -> robotNotFound(name));
    }

    /**
     * Denyut dari robot.
     *
     * <p>Robot yang belum dikenal MENDAFTARKAN DIRINYA di sini, bukan ditolak.
     * Memasang JakRunner di mesin baru lalu harus membuka dasbor untuk
     * mendaftarkannya lebih dulu adalah langkah yang selalu terlupakan, dan
     * gejalanya — robot menyala tapi tidak muncul di mana pun — tidak
     * mengarahkan siapa pun ke langkah yang terlupa itu.
     */
    @Transactional
    public HeartbeatResponse recordHeartbeat(ForgeHubPrincipal principal, String name, HeartbeatRequest heartbeat) {
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

        return HeartbeatResponse.at(Timestamps.nowText());
    }

    @Transactional
    public void create(ForgeHubPrincipal principal, CreateRobotRequest request) {
        UUID tenantId = principal.tenantId();

        if (robotRepository.existsByName(tenantId, request.name())) {
            throw ApiException.conflict("Robot '" + request.name() + "' sudah ada.");
        }

        robotRepository.insert(tenantId, request.name(), request.machineName(), request.username(),
                request.type(), request.environment(), request.description());

        ensureMachineRegistered(tenantId, request.machineName());
    }

    public void delete(ForgeHubPrincipal principal, String name) {
        if (robotRepository.deleteByName(principal.tenantId(), name) == 0) throw robotNotFound(name);
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
