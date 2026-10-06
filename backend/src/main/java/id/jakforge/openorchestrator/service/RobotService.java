package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Strings;
import id.jakforge.openorchestrator.common.Timestamps;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.dto.request.CreateRobotRequest;
import id.jakforge.openorchestrator.dto.request.HeartbeatRequest;
import id.jakforge.openorchestrator.dto.request.UpdateRobotRequest;
import id.jakforge.openorchestrator.dto.response.HeartbeatResponse;
import id.jakforge.openorchestrator.model.JobCommands;
import id.jakforge.openorchestrator.model.JobState;
import id.jakforge.openorchestrator.model.LogLevel;
import id.jakforge.openorchestrator.model.MachineTypes;
import id.jakforge.openorchestrator.model.RobotResolution;
import id.jakforge.openorchestrator.model.RobotStatus;
import id.jakforge.openorchestrator.model.Severity;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.repository.RobotRepository;
import id.jakforge.openorchestrator.security.AssistantSignIn;
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
    private static final String SESSION_POLICY_DEFAULT = "Logoff";
    private static final int MAX_ASSISTANT_NAME_ATTEMPTS = 20;

    private final RobotRepository robotRepository;
    private final MachineRepository machineRepository;
    private final JobRepository jobRepository;
    private final AlertRepository alertRepository;
    private final LogRepository logRepository;
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
     * <p>Jawabannya membawa perintah untuk job robot ini ({@code heartbeat.commands}):
     * StopJob untuk yang diminta berhenti — ditambah KillJob kalau diminta
     * dimatikan paksa — dan PauseJob/ResumeJob untuk yang diminta dijeda atau
     * dilanjutkan dari dasbor. Tanpa itu, tombol-tombol itu di dasbor tidak
     * pernah sampai ke robot.
     *
     * <p>Denyut juga menyebut job yang sedang ditahan robot ({@code pausedJobId});
     * itu yang membuat dasbor menampilkan "Dijeda", dari mana pun jedanya.
     */
    @Transactional
    public HeartbeatResponse recordHeartbeat(OpenOrchestratorPrincipal principal, String name, HeartbeatRequest heartbeat) {
        UUID tenantId = principal.tenantId();

        // Robot yang sedang menjalankan automasi lokal (V14) sibuk, apa pun
        // status yang ia sebut — dan tidak diberi job sampai selesai.
        String status = heartbeat.localRun() != null ? RobotStatus.BUSY.name()
                : RobotStatus.fromHeartbeat(heartbeat.status()).name();

        if (robotRepository.existsByName(tenantId, name)) {
            robotRepository.recordHeartbeat(tenantId, name, heartbeat.machineName(), principal.username(),
                    status, heartbeat.cpuPercent(), heartbeat.memoryMb(), heartbeat.localRun());

            // Robot lama yang kini berdenyut dari komputer lain: mesin barunya
            // didaftarkan juga (dan masuk folder bawaan, V12). Tanpa baris
            // mesin, robot itu tidak bisa mengambil job di folder mana pun.
            ensureMachineRegistered(tenantId, heartbeat.machineName());
        } else {
            robotRepository.registerFromHeartbeat(tenantId, name, heartbeat.machineName(), principal.username(),
                    status, heartbeat.cpuPercent(), heartbeat.memoryMb(), heartbeat.localRun());

            ensureMachineRegistered(tenantId, heartbeat.machineName());

            alertRepository.insert(tenantId, Severity.Info, "Robot baru terdaftar",
                    "Robot '" + name + "' menyambung untuk pertama kali.", ALERT_SOURCE);
        }

        recordPause(tenantId, name, heartbeat);

        List<JobCommands.V1Job> jobs = jobRepository.findCommandStateForV1Robot(tenantId, name).stream()
                .map(job -> new JobCommands.V1Job(
                        Uuids.parseOrNull((String) job.get("id")),
                        JobState.valueOf((String) job.get("state")),
                        Boolean.TRUE.equals(job.get("pauseRequested")),
                        Boolean.TRUE.equals(job.get("kill"))))
                .toList();

        return new HeartbeatResponse(true, Timestamps.nowText(),
                JobCommands.forV1(jobs, heartbeat.pausedJobId(), heartbeat.pauseSource()));
    }

    /** Jeda menurut robot dicatat, dan setiap perubahannya masuk ke log job-nya. */
    private void recordPause(UUID tenantId, String robotName, HeartbeatRequest heartbeat) {
        JobRepository.PauseChanges changes = jobRepository.recordV1Pause(tenantId, robotName,
                heartbeat.pausedJobId(), heartbeat.pauseSource());

        for (Map<String, Object> job : changes.paused()) {
            logRepository.insertJobEntry(tenantId, LogLevel.INFO,
                    JobCommands.SOURCE_DASHBOARD.equals(job.get("pauseSource"))
                            ? "Robot menjeda pekerjaan atas permintaan dasbor."
                            : "Pekerjaan dijeda langsung di PC robot.",
                    robotName, (String) job.get("processName"), Uuids.parseOrNull((String) job.get("id")));
        }

        for (Map<String, Object> job : changes.resumed()) {
            logRepository.insertJobEntry(tenantId, LogLevel.INFO, "Robot melanjutkan pekerjaan.",
                    robotName, (String) job.get("processName"), Uuids.parseOrNull((String) job.get("id")));
        }
    }

    /**
     * Robot attended untuk Open Assistant yang masuk lewat dasbor: satu per
     * pengguna dan komputer ("fajar-DESKTOP-01"). Yang sudah ada dipakai lagi
     * kalau memang milik pengguna itu dan bukan robot unattended; nama yang
     * terpakai robot lain diberi akhiran "-2", "-3", dan seterusnya.
     *
     * <p>Dibuat di sini — bukan menunggu denyut pertama — supaya nama yang
     * dijawab ke Open Assistant pasti miliknya, dan robotnya langsung tampil
     * di dasbor.
     *
     * <p>Sengaja tanpa {@code @Transactional} sendiri: dipanggil di dalam
     * transaksi penukaran kode, yang menyimpan tanda "kode sudah dipakai"
     * walaupun langkah ini gagal. Transaksi bersarang yang gagal di sini akan
     * menandai seluruh transaksi itu untuk dibatalkan.
     *
     * @return nama robot yang dipakai Open Assistant untuk denyut dan job
     */
    public String ensureAssistantRobot(UUID tenantId, String username, String machineName) {
        String baseName = AssistantSignIn.robotNameFor(username, machineName);

        for (int attempt = 1; attempt <= MAX_ASSISTANT_NAME_ATTEMPTS; attempt++) {
            String name = attempt == 1 ? baseName : baseName + "-" + attempt;
            Map<String, Object> robot = robotRepository.findByName(tenantId, name).orElse(null);

            if (robot == null) {
                robotRepository.insert(tenantId, name, machineName, username, ATTENDED, "Production",
                        "Open Assistant (masuk lewat dasbor).", null, null, null, false, SESSION_POLICY_DEFAULT,
                        RobotResolution.DEFAULT);

                ensureMachineRegistered(tenantId, machineName);

                alertRepository.insert(tenantId, Severity.Info, "Robot baru terdaftar",
                        "Robot '" + name + "' dibuat untuk Open Assistant " + username + " di " + machineName + ".",
                        ALERT_SOURCE);

                return name;
            }

            if (username.equalsIgnoreCase((String) robot.get("username")) && robot.get("machineId") == null) {
                return name;
            }
        }

        throw ApiException.conflict("Nama robot untuk " + username + " di " + machineName
                + " sudah dipakai robot lain. Ganti nama robot yang bentrok di dasbor, lalu sambungkan lagi.");
    }

    @Transactional
    public void create(OpenOrchestratorPrincipal principal, CreateRobotRequest request) {
        UUID tenantId = principal.tenantId();

        if (robotRepository.existsByName(tenantId, request.name())) {
            throw ApiException.conflict("Robot '" + request.name() + "' sudah ada.");
        }

        RobotResolution resolution = requireValid(RobotResolution.DEFAULT.with(request.resolutionWidth(),
                request.resolutionHeight(), request.resolutionDepth()));

        ensureMachineRegistered(tenantId, request.machineName());

        UUID machineId = unattendedMachineId(tenantId, request.type(), request.machineName());
        boolean passwordLocal = Boolean.TRUE.equals(request.windowsPasswordLocal());
        String password = passwordLocal || request.windowsPassword() == null ? null
                : secretBox.protect(request.windowsPassword());

        robotRepository.insert(tenantId, request.name(), request.machineName(), request.username(),
                request.type(), request.environment(), request.description(), machineId,
                request.windowsUsername(), password, passwordLocal, request.sessionPolicy(), resolution);

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

        // Resolusi sesi (V13): yang dikirim di atas yang tersimpan. Mesinnya
        // dinaikkan versi setelannya di bawah, jadi agent masuk ulang dan
        // memakai resolusi baru pada job berikutnya.
        RobotResolution resolution = requireValid(new RobotResolution(intOf(current.get("resolutionWidth")),
                intOf(current.get("resolutionHeight")), intOf(current.get("resolutionDepth")))
                .with(request.resolutionWidth(), request.resolutionHeight(), request.resolutionDepth()));

        robotRepository.updateConfig(tenantId, robotId, type, environment, description, machineId, machineName,
                windowsUsername, password, passwordLocal, passwordLocal, sessionPolicy, resolution);

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

    private static RobotResolution requireValid(RobotResolution resolution) {
        String problem = resolution.problem();
        if (problem != null) throw ApiException.badRequest(problem);
        return resolution;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
