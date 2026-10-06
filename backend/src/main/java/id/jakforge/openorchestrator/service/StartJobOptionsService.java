package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.model.MachineStates;
import id.jakforge.openorchestrator.model.RoleNames;
import id.jakforge.openorchestrator.model.RuntimeTypes;
import id.jakforge.openorchestrator.repository.FolderMachineRepository;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.repository.RobotRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.PermissionCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pilihan "Execution settings" di Start Job: tipe runtime, akun (robot), dan
 * mesin yang benar-benar bisa menjalankan job di sebuah folder.
 *
 * <p>Satu sumber untuk dua pemakai: dasbor, yang menampilkan pilihannya, dan
 * {@link JobService#create}, yang menolak kombinasi yang tidak akan pernah
 * diambil robot mana pun. Kalau keduanya menghitung sendiri, dasbor bisa
 * menawarkan pilihan yang lalu ditolak server — atau lebih buruk, server
 * menerima job yang menunggu selamanya.
 *
 * <p>Aturannya (sejak V12, mesin per folder):
 * <ul>
 *   <li>Mesin: yang TERDAFTAR di folder itu dan punya runtime — bukan semua
 *       mesin penyewa, dan bukan sekadar mesin tempat robot folder itu
 *       bekerja.</li>
 *   <li>Robot: ditugaskan ke folder itu, mesinnya terdaftar di folder itu, dan
 *       akunnya boleh mengambil job ({@code jobs.update}, aktif) — kecuali
 *       robot Robot Agent, yang masuk lewat machine key, bukan akun.</li>
 *   <li>Tipe runtime: yang dimiliki mesin-mesin itu, kecuali mesin yang
 *       dinonaktifkan.</li>
 * </ul>
 *
 * <p>Mesin yang DIPILIH harus online: memilih mesin yang mati berarti job yang
 * menunggu tanpa kepastian. "Mesin mana pun" boleh menunggu — robot yang
 * mengambilnya nanti pasti di mesin folder ini (lihat klaim di JobRepository).
 */
@Service
@RequiredArgsConstructor
public class StartJobOptionsService {

    /** Izin yang dipakai robot v1 untuk mengambil job (GET /api/jobs/next). */
    static final String CLAIM_PERMISSION = "jobs.update";

    /** errorCode: mesin yang diminta tidak terdaftar di folder proses. */
    public static final String MACHINE_NOT_ASSIGNED = "MACHINE_NOT_ASSIGNED_TO_FOLDER";
    /** errorCode: mesin terdaftar, tetapi tidak online atau tidak aktif. */
    public static final String MACHINE_NOT_AVAILABLE = "MACHINE_NOT_AVAILABLE";

    private final RobotRepository robotRepository;
    private final MachineRepository machineRepository;
    private final FolderMachineRepository folderMachineRepository;
    private final OpenOrchestratorProperties properties;

    /** Satu robot yang bisa dipilih sebagai Akun. {@code self}: robot milik orang yang sedang membuka dasbor. */
    public record RobotOption(String name, String type, String status, String machineName, String username,
                              String userDisplayName, boolean self) {
    }

    /**
     * Satu mesin yang terdaftar di folder itu. {@code status}: ONLINE, OFFLINE,
     * DISCONNECTED, MAINTENANCE, atau DISABLED ({@link MachineStates}).
     * {@code runtimes}: tipe → jumlah, urutan katalog.
     */
    public record MachineOption(String id, String name, String type, boolean online, String status,
                                Map<String, Integer> runtimes) {
    }

    /**
     * @param runtimeTypes katalog tipe runtime, urutan tampil
     * @param machines     mesin yang terdaftar di folder ini dan punya runtime
     * @param robots       robot folder ini yang bisa mengambil job di salah satu mesin itu
     */
    public record StartOptions(List<String> runtimeTypes, List<MachineOption> machines, List<RobotOption> robots) {

        /** Tipe runtime yang dimiliki setidaknya satu mesin yang tidak dinonaktifkan, urutan katalog. */
        public List<String> availableRuntimeTypes() {
            Set<String> present = new LinkedHashSet<>();
            machines.stream()
                    .filter(machine -> !MachineStates.DISABLED_STATUS.equals(machine.status()))
                    .forEach(machine -> present.addAll(machine.runtimes().keySet()));
            return runtimeTypes.stream().filter(present::contains).toList();
        }

        public Optional<MachineOption> machine(String name) {
            return machines.stream().filter(machine -> machine.name().equals(name)).findFirst();
        }

        public Optional<RobotOption> robot(String name) {
            return robots.stream().filter(robot -> robot.name().equals(name)).findFirst();
        }
    }

    public StartOptions forFolder(OpenOrchestratorPrincipal principal, UUID folderId) {
        UUID tenantId = principal.tenantId();
        Map<String, Map<String, Integer>> runtimesById = machineRepository.findRuntimesForTenant(tenantId);

        List<MachineOption> machines = new ArrayList<>();

        for (Map<String, Object> machine : folderMachineRepository.findForFolder(tenantId, folderId,
                properties.agent().offlineAfter().toSeconds(), properties.robot().heartbeatTimeout().toSeconds())) {
            Map<String, Integer> runtimes = runtimesById.getOrDefault((String) machine.get("id"), Map.of());
            if (runtimes.isEmpty()) continue;

            String status = (String) machine.get("status");

            machines.add(new MachineOption((String) machine.get("id"), (String) machine.get("name"),
                    (String) machine.get("type"), MachineStates.ONLINE.equals(status), status,
                    new LinkedHashMap<>(runtimes)));
        }

        Set<String> registered = new LinkedHashSet<>();
        machines.forEach(machine -> registered.add(machine.name()));

        List<RobotOption> robots = robotRepository.findForStartJob(tenantId, folderId).stream()
                .filter(StartJobOptionsService::canClaim)
                .filter(robot -> robot.get("machineName") != null && registered.contains((String) robot.get("machineName")))
                .map(robot -> new RobotOption(
                        (String) robot.get("name"),
                        (String) robot.get("type"),
                        (String) robot.get("status"),
                        (String) robot.get("machineName"),
                        (String) robot.get("username"),
                        (String) robot.get("userDisplayName"),
                        robot.get("username") != null && robot.get("username").equals(principal.username())))
                .toList();

        return new StartOptions(RuntimeTypes.ALL, machines, robots);
    }

    /**
     * Tolak kombinasi yang tidak akan pernah diambil robot mana pun di folder
     * itu. Pesannya menyebut apa yang salah, bukan sekadar "tidak valid":
     * yang membacanya harus tahu apakah yang kurang runtime, mesin, atau
     * penugasan robotnya.
     *
     * <p>Mesin di luar folder proses dijawab {@code 409 MACHINE_NOT_ASSIGNED_TO_FOLDER}
     * — termasuk yang dikirim tangan, tanpa dasbor. Mesin terdaftar yang
     * tidak online (atau dalam pemeliharaan, atau dinonaktifkan) dijawab
     * {@code 409 MACHINE_NOT_AVAILABLE}, dengan statusnya di {@code state}.
     */
    public void validate(StartOptions options, String runtimeType, String machineName, String robotName) {
        if (runtimeType != null && !options.availableRuntimeTypes().contains(runtimeType)) {
            throw ApiException.badRequest("Tidak ada mesin di folder ini yang punya runtime " + runtimeType
                    + ". Tambahkan runtime itu ke mesin di folder ini.");
        }

        MachineOption machine = null;

        if (machineName != null) {
            machine = options.machine(machineName).orElseThrow(() -> ApiException.conflict(
                    "Mesin '" + machineName + "' tidak terdaftar pada folder proses ini.").withCode(MACHINE_NOT_ASSIGNED));

            if (!machine.online()) {
                throw ApiException.conflict("Mesin '" + machineName + "' sedang " + statusWord(machine.status())
                        + ", jadi tidak bisa dipilih. Pilih mesin yang online, atau Mesin mana pun.")
                        .withCode(MACHINE_NOT_AVAILABLE).withState(machine.status());
            }

            if (runtimeType != null && !machine.runtimes().containsKey(runtimeType)) {
                throw ApiException.badRequest("Mesin '" + machineName + "' tidak punya runtime " + runtimeType + ".");
            }
        }

        if (robotName != null) {
            RobotOption robot = options.robot(robotName).orElseThrow(() -> ApiException.badRequest(
                    "Robot '" + robotName + "' tidak bisa menjalankan pekerjaan di folder ini."));

            if (machine != null && !Objects.equals(robot.machineName(), machine.name())) {
                throw ApiException.badRequest("Robot '" + robotName + "' tidak berada di mesin '" + machineName + "'.");
            }

            if (runtimeType != null && options.machine(robot.machineName())
                    .map(own -> !own.runtimes().containsKey(runtimeType)).orElse(true)) {
                throw ApiException.badRequest("Mesin robot '" + robotName + "' tidak punya runtime " + runtimeType + ".");
            }
        }
    }

    /**
     * Permintaan yang hanya menyebut robot (Studio, Start Job lama): robotnya
     * boleh saja belum dikenal, tapi robot yang dikenal di mesin yang TIDAK
     * terdaftar di folder proses tidak akan pernah mengambil job itu.
     */
    public void requireRobotMachineInFolder(UUID tenantId, UUID folderId, String robotName) {
        folderMachineRepository.findRobotMachine(tenantId, folderId, robotName)
                .filter(row -> !Boolean.TRUE.equals(row.get("registered")))
                .ifPresent(row -> {
                    throw ApiException.conflict("Mesin '" + row.get("name") + "' tempat robot '" + robotName
                            + "' bekerja tidak terdaftar pada folder proses ini.").withCode(MACHINE_NOT_ASSIGNED);
                });
    }

    /**
     * Jalankan ulang: mesin yang diminta job lama harus masih terdaftar di
     * folder prosesnya. Statusnya tidak diperiksa — job ulangan boleh
     * menunggu mesinnya online lagi.
     */
    public void requireMachineInFolder(UUID tenantId, UUID folderId, String machineName) {
        if (!folderMachineRepository.isAssignedByName(tenantId, folderId, machineName)) {
            throw ApiException.conflict("Mesin '" + machineName + "' tidak terdaftar pada folder proses ini.")
                    .withCode(MACHINE_NOT_ASSIGNED);
        }
    }

    /** "offline", "terputus", ... — untuk kalimat galat, sebelum diterjemahkan dasbor. */
    static String statusWord(String status) {
        return switch (status == null ? "" : status) {
            case MachineStates.DISCONNECTED -> "terputus";
            case MachineStates.MAINTENANCE_STATUS -> "dalam pemeliharaan";
            case MachineStates.DISABLED_STATUS -> "dinonaktifkan";
            default -> "offline";
        };
    }

    /**
     * Robot Robot Agent masuk dengan machine key, jadi tidak bergantung pada
     * akun siapa pun. Robot lain mengambil job dengan akun yang ia pakai masuk:
     * akun itu harus aktif dan boleh {@code jobs.update}. Robot yang akunnya
     * belum diketahui — belum pernah berdenyut — diberi kesempatan.
     */
    private static boolean canClaim(Map<String, Object> robot) {
        if (Boolean.TRUE.equals(robot.get("agentRobot")) || robot.get("username") == null) return true;
        if (!Boolean.TRUE.equals(robot.get("userFound")) || !Boolean.TRUE.equals(robot.get("userActive"))) return false;

        return RoleNames.ADMINISTRATOR.equals(robot.get("userRole"))
                || PermissionCatalog.matches(PermissionCatalog.parsePatterns((String) robot.get("rolePermissions")),
                CLAIM_PERMISSION);
    }
}
