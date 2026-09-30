package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.model.RoleNames;
import id.jakforge.openorchestrator.model.RuntimeTypes;
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
 * <p>Aturannya:
 * <ul>
 *   <li>Robot: ditugaskan ke folder itu, akunnya boleh mengambil job
 *       ({@code jobs.update}, aktif) — kecuali robot Robot Agent, yang masuk
 *       lewat machine key, bukan akun — dan mesinnya dikenal serta punya
 *       runtime.</li>
 *   <li>Mesin: tempat robot-robot itu berjalan, yang punya runtime.</li>
 *   <li>Tipe runtime: yang dimiliki mesin-mesin itu.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StartJobOptionsService {

    /** Izin yang dipakai robot v1 untuk mengambil job (GET /api/jobs/next). */
    static final String CLAIM_PERMISSION = "jobs.update";

    private final RobotRepository robotRepository;
    private final MachineRepository machineRepository;
    private final OpenOrchestratorProperties properties;

    /** Satu robot yang bisa dipilih sebagai Akun. {@code self}: robot milik orang yang sedang membuka dasbor. */
    public record RobotOption(String name, String type, String status, String machineName, String username,
                              String userDisplayName, boolean self) {
    }

    /** Satu mesin yang bisa dipilih. {@code runtimes}: tipe → jumlah, urutan katalog. */
    public record MachineOption(String name, String type, boolean online, Map<String, Integer> runtimes) {
    }

    /**
     * @param runtimeTypes katalog tipe runtime, urutan tampil
     * @param machines     mesin folder ini yang punya runtime
     * @param robots       robot folder ini yang bisa mengambil job di salah satu mesin itu
     */
    public record StartOptions(List<String> runtimeTypes, List<MachineOption> machines, List<RobotOption> robots) {

        /** Tipe runtime yang dimiliki setidaknya satu mesin, urutan katalog. */
        public List<String> availableRuntimeTypes() {
            Set<String> present = new LinkedHashSet<>();
            machines.forEach(machine -> present.addAll(machine.runtimes().keySet()));
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

        List<Map<String, Object>> candidates = robotRepository.findForStartJob(tenantId, folderId).stream()
                .filter(StartJobOptionsService::canClaim)
                .filter(robot -> robot.get("machineName") != null)
                .toList();

        Set<String> machineNames = new LinkedHashSet<>();
        candidates.forEach(robot -> machineNames.add((String) robot.get("machineName")));

        Map<String, Map<String, Integer>> runtimesById = machineRepository.findRuntimesForTenant(tenantId);

        List<MachineOption> machines = new ArrayList<>();
        for (Map<String, Object> machine : machineRepository.findConnectionByNames(tenantId, machineNames,
                properties.agent().offlineAfter().toSeconds(), properties.robot().heartbeatTimeout().toSeconds())) {
            Map<String, Integer> runtimes = runtimesById.getOrDefault((String) machine.get("id"), Map.of());
            if (runtimes.isEmpty()) continue;

            machines.add(new MachineOption((String) machine.get("name"), (String) machine.get("type"),
                    Boolean.TRUE.equals(machine.get("online")), new LinkedHashMap<>(runtimes)));
        }

        Set<String> usableMachines = new LinkedHashSet<>();
        machines.forEach(machine -> usableMachines.add(machine.name()));

        List<RobotOption> robots = candidates.stream()
                .filter(robot -> usableMachines.contains((String) robot.get("machineName")))
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
     */
    public void validate(StartOptions options, String runtimeType, String machineName, String robotName) {
        if (runtimeType != null && !options.availableRuntimeTypes().contains(runtimeType)) {
            throw ApiException.badRequest("Tidak ada mesin di folder ini yang punya runtime " + runtimeType
                    + ". Tambahkan runtime itu ke mesin di folder ini.");
        }

        MachineOption machine = null;

        if (machineName != null) {
            machine = options.machine(machineName).orElseThrow(() -> ApiException.badRequest(
                    "Mesin '" + machineName + "' tidak tersedia di folder ini."));

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
