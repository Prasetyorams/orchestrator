package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.RequestBodies;
import id.jakforge.openorchestrator.common.SecretRedaction;
import id.jakforge.openorchestrator.common.Timestamps;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.dto.request.AgentHeartbeatRequest;
import id.jakforge.openorchestrator.dto.request.AgentStateReport;
import id.jakforge.openorchestrator.dto.request.LogBatchRequest;
import id.jakforge.openorchestrator.dto.response.AgentHeartbeatResponse;
import id.jakforge.openorchestrator.dto.response.AgentStateResponse;
import id.jakforge.openorchestrator.dto.response.LogWriteResponse;
import id.jakforge.openorchestrator.dto.response.WindowsCredentialResponse;
import id.jakforge.openorchestrator.model.AgentErrorCodes;
import id.jakforge.openorchestrator.model.JobCommands;
import id.jakforge.openorchestrator.model.JobState;
import id.jakforge.openorchestrator.model.JobTransitions;
import id.jakforge.openorchestrator.model.LogLevel;
import id.jakforge.openorchestrator.model.RobotStatus;
import id.jakforge.openorchestrator.repository.AttachmentRepository;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.repository.RobotRepository;
import id.jakforge.openorchestrator.security.JwtService;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.SecretBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Robot Agent unattended: denyut, klaim job, laporan keadaan, akun Windows,
 * catatan, dan lampiran (/api/agent, ROBOT-API.md bagian 2).
 *
 * <p>Pembagiannya: Orchestrator memutuskan APA dan KAPAN — job mana untuk
 * robot mana, batas waktu, percobaan ulang, kapan job dianggap hilang. Agent
 * memutuskan BAGAIMANA — menyiapkan sesi, menjalankan dan menghentikan
 * Executor, menyimpan laporan sampai terkirim.
 *
 * <p>Setiap pemanggil sudah dipastikan agent dengan kunci yang masih berlaku
 * (PermissionInterceptor). Yang diperiksa di sini adalah KEPEMILIKAN: robot
 * dan job harus milik mesin agent itu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    static final String JOB_NOT_FOUND = "Pekerjaan tidak ada.";
    static final String NOT_YOUR_JOB = "Pekerjaan ini bukan milik robot di mesin ini.";

    /** Batas panjang medan info job dan pesan catatan. */
    static final int MAX_INFO_LENGTH = 2000;
    static final int MAX_LOG_MESSAGE_BYTES = 8 * 1024;

    /** Jeda berhenti rapi kalau proses tidak menyebutnya. */
    static final int DEFAULT_STOP_GRACE_SECONDS = 30;

    /** Token executor untuk job tanpa batas waktu: tetap dicabut begitu job selesai. */
    static final Duration EXECUTOR_TOKEN_WITHOUT_TIMEOUT = Duration.ofDays(7);
    static final Duration EXECUTOR_TOKEN_MARGIN = Duration.ofHours(1);

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private final MachineRepository machineRepository;
    private final RobotRepository robotRepository;
    private final JobRepository jobRepository;
    private final LogRepository logRepository;
    private final AttachmentRepository attachmentRepository;
    private final AuditService auditService;
    private final JobOutcomeService jobOutcomeService;
    private final JwtService jwtService;
    private final SecretBox secretBox;
    private final OpenOrchestratorProperties properties;

    // -----------------------------------------------------------------
    // Denyut
    // -----------------------------------------------------------------

    /**
     * Satu denyut untuk seluruh mesin.
     *
     * <p>Urutannya penting: keadaan robot dicatat dulu (supaya robotnya tidak
     * tampil Offline), lalu job yang disebut agent dikonfirmasi, lalu job yang
     * TIDAK disebut dibereskan, dan terakhir perintah disusun dari keadaan job
     * sesudah semua itu.
     */
    @Transactional
    public AgentHeartbeatResponse heartbeat(OpenOrchestratorPrincipal agent, AgentHeartbeatRequest request) {
        UUID machineId = agent.machineId();
        Map<String, Object> machine = machineRepository.findById(machineId)
                .orElseThrow(() -> ApiException.unauthorized("Mesin ini sudah dihapus.").withCode("MachineDeleted"));

        machineRepository.recordAgentHeartbeat(machineId, request.agentVersion(), request.cpuPercent(),
                request.memoryUsedMb(), request.memoryTotalMb(), request.maxInteractiveSessions());

        OpenOrchestratorProperties.Agent settings = properties.agent();
        int leaseSeconds = ((Number) machine.get("leaseSeconds")).intValue();
        int graceSeconds = Math.toIntExact(settings.reconcileGrace().toSeconds());

        Map<UUID, Map<String, Object>> commands = new LinkedHashMap<>();

        for (AgentHeartbeatRequest.RobotReport report : request.robots()) {
            String status = "Busy".equalsIgnoreCase(report.state()) ? RobotStatus.BUSY.name() : RobotStatus.AVAILABLE.name();

            int updated = robotRepository.recordAgentReport(report.robotId(), machineId, status,
                    normalizeWord(report.state()), report.sessionId(), normalizeWord(report.sessionState()),
                    report.sessionReady(), truncate(report.reasonCode(), 60), truncate(report.reasonText(), 400),
                    normalizeWord(report.executorState()), report.executorPid(), request.cpuPercent(),
                    request.memoryUsedMb());

            // Robot yang bukan milik mesin ini dilewati tanpa galat: bisa saja
            // baru dipindah ke mesin lain lewat dasbor, dan agent belum tahu.
            if (updated == 0 || !report.jobIdsSent()) continue;

            jobRepository.confirmHeldJobs(report.robotId(), report.activeJobIds(), leaseSeconds);

            for (Map<String, Object> lost : jobRepository.failUnreportedJobs(report.robotId(), report.activeJobIds(),
                    graceSeconds)) {
                logRepository.insertJobEntry(agent.tenantId(), LogLevel.WARN,
                        "Robot Agent tidak lagi menjalankan pekerjaan ini (tidak disebut di denyutnya).",
                        (String) lost.get("robotName"), (String) lost.get("processName"),
                        Uuids.parseOrNull((String) lost.get("id")));
                jobOutcomeService.afterFinished(lost);
            }

            // Job yang masih dipegang agent padahal sudah selesai di sini —
            // mis. sudah diulang di robot lain sesudah lease-nya habis. Dua
            // eksekusi dari pekerjaan yang sama tidak boleh berlanjut.
            for (Map<String, Object> finished : jobRepository.findFinishedAmong(machineId, report.activeJobIds())) {
                UUID jobId = Uuids.parseOrNull((String) finished.get("id"));
                commands.put(jobId, command(JobCommands.STOP, jobId, graceOf(finished.get("stopGraceSeconds"))));
            }

            if (report.pausedSent()) recordPause(agent.tenantId(), report);
        }

        for (Map<String, Object> stop : jobRepository.findStopRequestsForMachine(machineId, DEFAULT_STOP_GRACE_SECONDS)) {
            UUID jobId = Uuids.parseOrNull((String) stop.get("id"));
            boolean kill = Boolean.TRUE.equals(stop.get("kill"));

            commands.put(jobId, kill
                    ? command(JobCommands.KILL, jobId, null)
                    : command(JobCommands.STOP, jobId, graceOf(stop.get("graceSeconds"))));
        }

        // Jeda: dari keadaan SESUDAH laporan jeda agent dicatat. Agent yang
        // belum mengenal jeda tidak pernah melaporkan job yang ditahan, jadi ia
        // terus menerima PauseJob — dan mengabaikannya, seperti semua jenis
        // perintah yang tidak ia kenal.
        for (Map<String, Object> job : jobRepository.findPauseStateForMachine(machineId)) {
            UUID jobId = Uuids.parseOrNull((String) job.get("id"));

            JobCommands.pauseCommand(Boolean.TRUE.equals(job.get("pauseRequested")),
                            Boolean.TRUE.equals(job.get("paused")), (String) job.get("pauseSource"))
                    .ifPresent(type -> commands.putIfAbsent(jobId, command(type, jobId, null)));
        }

        int settingsVersion = machineRepository.findSettingsVersion(machineId).orElse(1);

        return new AgentHeartbeatResponse(Timestamps.nowText(), new ArrayList<>(commands.values()), settingsVersion,
                settings.heartbeatSeconds(), settings.heartbeatBusySeconds());
    }

    /**
     * Tipe runtime mesin ini yang masih punya tempat, urutan katalog. Setiap
     * tipe punya batasnya sendiri: dua runtime Production dan satu Testing
     * berarti job Testing kedua menunggu walaupun satu Production kosong.
     */
    private List<String> freeRuntimeTypes(UUID machineId) {
        Map<String, Integer> runtimes = machineRepository.findRuntimes(machineId);
        Map<String, Long> held = jobRepository.countHeldOnMachineByRuntime(machineId);

        return runtimes.entrySet().stream()
                .filter(runtime -> held.getOrDefault(runtime.getKey(), 0L) < runtime.getValue())
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Jeda menurut agent dicatat, dan setiap perubahannya masuk ke log job-nya. */
    private void recordPause(UUID tenantId, AgentHeartbeatRequest.RobotReport report) {
        JobRepository.PauseChanges changes = jobRepository.recordAgentPause(report.robotId(), report.pausedJobs());

        for (Map<String, Object> job : changes.paused()) {
            logRepository.insertJobEntry(tenantId, LogLevel.INFO,
                    JobCommands.SOURCE_DASHBOARD.equals(job.get("pauseSource"))
                            ? "Robot menjeda pekerjaan atas permintaan dasbor."
                            : "Pekerjaan dijeda langsung di PC robot.",
                    (String) job.get("robotName"), (String) job.get("processName"),
                    Uuids.parseOrNull((String) job.get("id")));
        }

        for (Map<String, Object> job : changes.resumed()) {
            logRepository.insertJobEntry(tenantId, LogLevel.INFO, "Robot melanjutkan pekerjaan.",
                    (String) job.get("robotName"), (String) job.get("processName"),
                    Uuids.parseOrNull((String) job.get("id")));
        }
    }

    // -----------------------------------------------------------------
    // Klaim
    // -----------------------------------------------------------------

    /**
     * Ambil job berikutnya untuk satu robot.
     *
     * <p>Kosong — bukan galat — kalau robot atau mesinnya belum bisa menerima
     * job: sesinya tidak siap, sedang memegang job lain, akun Windows-nya
     * gagal login, atau slot mesin penuh. Alasan itu terlihat di dasbor lewat
     * denyutnya, bukan lewat jawaban klaim yang diulang tiap beberapa detik.
     */
    @Transactional
    public Optional<Map<String, Object>> claim(OpenOrchestratorPrincipal agent, String robotIdText) {
        UUID robotId = Uuids.parseOrNull(robotIdText);
        if (robotId == null) throw ApiException.badRequest("robotId wajib diisi.");

        UUID machineId = agent.machineId();

        // Mesin dikunci lebih dulu, baru robotnya — urutan yang sama di setiap
        // klaim, supaya dua klaim bersamaan tidak saling menunggu selamanya.
        Map<String, Object> machine = machineRepository.lockById(machineId)
                .orElseThrow(() -> ApiException.unauthorized("Mesin ini sudah dihapus.").withCode("MachineDeleted"));

        Map<String, Object> robot = robotRepository.lockForMachine(robotId, machineId)
                .orElseThrow(() -> ApiException.forbidden("Robot ini bukan milik mesin ini.").withCode("NotYourRobot"));

        if (robot.get("needsAttention") != null
                || "Error".equalsIgnoreCase((String) robot.get("agentState"))
                || Boolean.FALSE.equals(robot.get("sessionReady"))
                || jobRepository.robotHoldsJob(robotId)) {
            return Optional.empty();
        }

        int slots = AgentAuthService.effectiveSlots(((Number) machine.get("slots")).intValue(),
                (Integer) machine.get("maxInteractiveSessions"));

        if (jobRepository.countHeldOnMachine(machineId) >= slots) return Optional.empty();

        List<String> freeRuntimeTypes = freeRuntimeTypes(machineId);
        if (freeRuntimeTypes.isEmpty()) return Optional.empty();

        UUID tenantId = agent.tenantId();
        String robotName = (String) robot.get("name");
        String machineName = (String) machine.get("name");
        int leaseSeconds = ((Number) machine.get("leaseSeconds")).intValue();

        Optional<Map<String, Object>> claimed = jobRepository.claimNextForAgent(tenantId, robotId, robotName,
                machineId, machineName, leaseSeconds, properties.agent().retryAvoidWindow().toSeconds(),
                freeRuntimeTypes);

        if (claimed.isEmpty()) return Optional.empty();

        Map<String, Object> job = claimed.get();
        UUID jobId = Uuids.parseOrNull((String) job.get("id"));
        UUID folderId = Uuids.parseOrNull((String) job.get("folderId"));
        String processName = (String) job.get("processName");

        Map<String, Object> plan = jobRepository.findExecutionPlan(tenantId, folderId, processName).orElseGet(HashMap::new);

        String packageName = (String) plan.get("packageName");
        String packageVersion = (String) plan.get("packageVersion");
        Integer timeoutSeconds = (Integer) plan.get("timeoutSeconds");
        int stopGraceSeconds = graceOf(plan.get("stopGraceSeconds"));

        jobRepository.recordExecutionPlan(jobId, packageName, packageVersion, (String) plan.get("sha256"),
                timeoutSeconds, stopGraceSeconds);

        Duration tokenTtl = timeoutSeconds == null
                ? EXECUTOR_TOKEN_WITHOUT_TIMEOUT
                : Duration.ofSeconds(timeoutSeconds).plus(EXECUTOR_TOKEN_MARGIN);

        String executorToken = jwtService.issueExecutorToken(robotId, tenantId, robotName, jobId, folderId, tokenTtl)
                .token();

        logRepository.insertJobEntry(tenantId, LogLevel.INFO,
                "Diambil Robot Agent di mesin " + machineName + " untuk robot " + robotName + ".", robotName,
                processName, jobId);

        Map<String, Object> packageInfo = new LinkedHashMap<>();
        packageInfo.put("name", packageName);
        packageInfo.put("version", packageVersion);
        packageInfo.put("sha256", plan.get("sha256"));
        packageInfo.put("sizeBytes", plan.get("sizeBytes"));
        packageInfo.put("url", packageName == null || packageVersion == null ? null
                : "/api/packages/" + encodePath(packageName) + "/" + encodePath(packageVersion) + "/content");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", jobId.toString());
        result.put("attempt", job.get("attempt"));
        result.put("processName", processName);
        result.put("folderId", folderId == null ? null : folderId.toString());
        result.put("priority", job.get("priority"));
        result.put("runtimeType", job.get("runtimeType"));
        result.put("package", packageInfo);
        result.put("entryPoint", plan.get("entryPoint"));
        result.put("inputJson", job.get("inputJson"));
        result.put("leaseExpiresAt", job.get("leaseExpiresAt"));
        result.put("timeoutSeconds", timeoutSeconds);
        result.put("stopGraceSeconds", stopGraceSeconds);
        result.put("sessionPolicy", robot.get("sessionPolicy"));
        result.put("windowsPasswordLocal", robot.get("windowsPasswordLocal"));
        result.put("executorToken", executorToken);

        return Optional.of(result);
    }

    // -----------------------------------------------------------------
    // Laporan keadaan
    // -----------------------------------------------------------------

    /**
     * Terapkan satu laporan dari outbox agent.
     *
     * <p>Laporan yang ditolak (409) TIDAK membatalkan transaksinya: nomor
     * urutnya dan catatan "laporan terlambat" tetap tersimpan, supaya kiriman
     * ulang yang sama dijawab sebagai duplikat dan operator tahu hasil asli job
     * yang sudah diulang.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AgentStateResponse reportState(OpenOrchestratorPrincipal agent, String jobIdText, AgentStateReport report) {
        UUID jobId = Uuids.parseOrNull(jobIdText);
        if (jobId == null) throw ApiException.notFound(JOB_NOT_FOUND);

        if (report.seq() == null || report.seq() < 1) {
            throw ApiException.badRequest("seq wajib diisi, mulai dari 1.").withCode("SeqMissing");
        }

        JobState reported = JobState.parse(report.state());

        if (reported == null || !JobTransitions.AGENT_REPORTABLE.contains(reported)) {
            throw ApiException.badRequest("Keadaan yang boleh dilaporkan agent: PREPARING_SESSION, RUNNING, "
                    + "SUCCESSFUL, FAULTED, STOPPED.").withCode("InvalidState");
        }

        Map<String, Object> job = jobRepository.lockById(agent.tenantId(), jobId)
                .orElseThrow(() -> ApiException.notFound(JOB_NOT_FOUND));

        requireOwnJob(agent, job);

        JobState current = JobState.valueOf((String) job.get("state"));
        boolean stopRequested = job.get("stopRequestedAt") != null;

        var snapshot = new JobTransitions.Snapshot(current, Boolean.TRUE.equals(job.get("failureInferred")),
                Boolean.TRUE.equals(job.get("retried")), stopRequested, ((Number) job.get("lastSeq")).longValue());

        JobTransitions.Decision decision = JobTransitions.forAgent(snapshot, reported, report.seq());

        switch (decision.verdict()) {
            case DUPLICATE -> {
                return new AgentStateResponse(current.name(), stopRequested && !current.isFinished());
            }
            case REJECT -> {
                jobRepository.recordSeq(jobId, report.seq());

                if (decision.lateFinal()) {
                    logRepository.insertJobEntry(agent.tenantId(), LogLevel.WARN,
                            "Laporan terlambat dari Robot Agent: " + reported
                                    + (report.errorCode() == null ? "" : " (" + report.errorCode() + ")")
                                    + (report.info() == null ? "" : " — " + truncate(report.info(), 400))
                                    + ". Pekerjaan ini sudah " + current + " dan tidak diubah.",
                            (String) job.get("robotName"), (String) job.get("processName"), jobId);
                }

                throw ApiException.conflict("Pekerjaan ini sudah " + current + "; laporan " + reported + " ditolak.")
                        .withCode("InvalidTransition").withState(current.name());
            }
            default -> {
                // diterapkan di bawah
            }
        }

        JobState next = decision.state();
        String outputJson = acceptableOutput(agent, job, next, report.outputJson());
        String errorCode = next == JobState.FAULTED || next == JobState.STOPPED
                ? truncate(report.errorCode(), AgentErrorCodes.MAX_LENGTH)
                : null;
        Integer progress = report.progress() == null ? null : Math.clamp(report.progress(), 0, 100);
        int leaseSeconds = machineRepository.findById(agent.machineId())
                .map(m -> ((Number) m.get("leaseSeconds")).intValue())
                .orElse(180);

        jobRepository.applyAgentReport(jobId, next, report.seq(), progress, truncate(report.info(), MAX_INFO_LENGTH),
                errorCode, outputJson, report.sessionId(), truncate(report.windowsUser(), 200), report.executorPid(),
                leaseSeconds, decision.revived());

        if (decision.revived()) {
            logRepository.insertJobEntry(agent.tenantId(), LogLevel.INFO,
                    "Laporan asli Robot Agent (" + reported + ") menggantikan kesimpulan Orchestrator ("
                            + job.get("errorCode") + ").",
                    (String) job.get("robotName"), (String) job.get("processName"), jobId);
        }

        if (next.isFinished()) {
            Map<String, Object> outcome = new HashMap<>(job);
            outcome.put("state", next.name());
            outcome.put("errorCode", errorCode);
            outcome.put("info", report.info());
            outcome.put("tenantId", agent.tenantId().toString());

            logRepository.insertJobEntry(agent.tenantId(), next == JobState.FAULTED ? LogLevel.ERROR : LogLevel.INFO,
                    "Pekerjaan selesai: " + next + (errorCode == null ? "" : " (" + errorCode + ")") + ".",
                    (String) job.get("robotName"), (String) job.get("processName"), jobId);

            jobOutcomeService.afterFinished(outcome);
        }

        return new AgentStateResponse(next.name(), (next == JobState.STOPPING || stopRequested) && !next.isFinished());
    }

    // -----------------------------------------------------------------
    // Akun Windows
    // -----------------------------------------------------------------

    /**
     * Akun Windows robot, untuk SATU penyiapan sesi.
     *
     * <p>Hanya selama job itu sedang disiapkan (ASSIGNED atau
     * PREPARING_SESSION): sesudah itu tidak ada alasan agent membutuhkannya
     * lagi, dan sandi yang bisa diminta kapan saja adalah sandi yang bisa
     * dicuri kapan saja. Setiap pengambilan dicatat di jejak audit — tanpa
     * sandinya.
     */
    @Transactional
    public WindowsCredentialResponse windowsCredential(OpenOrchestratorPrincipal agent, String jobIdText) {
        UUID jobId = Uuids.parseOrNull(jobIdText);
        if (jobId == null) throw ApiException.notFound(JOB_NOT_FOUND);

        Map<String, Object> job = jobRepository.lockById(agent.tenantId(), jobId)
                .orElseThrow(() -> ApiException.notFound(JOB_NOT_FOUND));

        requireOwnJob(agent, job);

        String state = (String) job.get("state");

        if (!JobState.ASSIGNED.name().equals(state) && !JobState.PREPARING_SESSION.name().equals(state)) {
            throw ApiException.forbidden("Akun Windows hanya diberikan selama sesi pekerjaan ini disiapkan.")
                    .withCode("CredentialNotAvailable");
        }

        UUID robotId = Uuids.parseOrNull((String) job.get("robotId"));
        Map<String, Object> account = robotRepository.findWindowsAccount(robotId)
                .orElseThrow(() -> ApiException.notFound("Robot pekerjaan ini sudah dihapus."));

        if (Boolean.TRUE.equals(account.get("windowsPasswordLocal"))) {
            throw ApiException.conflict("Sandi Windows robot ini disimpan di mesin robot, bukan di Orchestrator.")
                    .withCode("PasswordStoredLocally");
        }

        String username = (String) account.get("windowsUsername");
        String password = secretBox.unprotect((String) account.get("windowsPassword"));

        if (username == null) {
            throw ApiException.conflict("Robot ini belum punya akun Windows. Isi di Tenant › Robots.")
                    .withCode("NoWindowsAccount");
        }

        if (password == null) {
            throw ApiException.conflict("Sandi Windows robot ini belum diisi, atau tidak bisa dibuka dengan "
                    + "signing.key yang terpasang.").withCode("NoWindowsPassword");
        }

        auditService.record(agent, "Robot", "Ambil akun Windows",
                account.get("name") + " · " + job.get("processName"),
                "POST /api/agent/jobs/" + jobId + "/windows-credential");

        return new WindowsCredentialResponse(username, password);
    }

    // -----------------------------------------------------------------
    // Catatan dan lampiran
    // -----------------------------------------------------------------

    /**
     * Catatan dari agent dan Executor.
     *
     * <p>Baris dari robot atau job milik mesin lain dilewati; baris yang
     * sudah pernah diterima (job dan seq yang sama) juga — kiriman ulang dari
     * outbox bukan kesalahan.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public LogWriteResponse writeLogs(OpenOrchestratorPrincipal agent, LogBatchRequest request) {
        if (request.lines() == null) throw ApiException.badRequest("Butuh { \"lines\": [ ... ] }.");

        if (request.lines().size() > AgentAuthService.MAX_LOG_LINES) {
            throw ApiException.tooLarge("Terlalu banyak baris dalam satu kiriman. Batasnya "
                    + AgentAuthService.MAX_LOG_LINES + ".");
        }

        UUID tenantId = agent.tenantId();
        String machineName = agent.username();

        Map<UUID, String> robotNames = new HashMap<>();
        for (Map<String, Object> robot : robotRepository.findForMachine(agent.machineId())) {
            robotNames.put(Uuids.parseOrNull((String) robot.get("id")), (String) robot.get("name"));
        }

        Map<UUID, Optional<Map<String, Object>>> jobs = new HashMap<>();
        int written = 0;

        for (Object item : request.lines()) {
            if (!(item instanceof Map<?, ?> map)) continue;

            Map<String, Object> line = (Map<String, Object>) map;
            String message = RequestBodies.text(line, "message");
            LogLevel level = LogLevel.parseOrInfo(RequestBodies.text(line, "level"));

            if (message == null || message.isBlank() || level.isVerbose()) continue;

            UUID robotId = Uuids.parseOrNull(RequestBodies.text(line, "robotId"));
            if (robotId != null && !robotNames.containsKey(robotId)) continue;

            UUID jobId = Uuids.parseOrNull(RequestBodies.text(line, "jobId"));
            Map<String, Object> job = null;

            if (jobId != null) {
                job = jobs.computeIfAbsent(jobId, id -> jobRepository.findOwnership(tenantId, id)
                        .filter(row -> agent.machineId().toString().equals(machineOf(row)))).orElse(null);

                if (job == null) continue;
            }

            String robotName = robotId != null ? robotNames.get(robotId) : job == null ? null : (String) job.get("robotName");

            written += logRepository.insertAgentLine(tenantId, level, truncateBytes(SecretRedaction.redact(message)),
                    robotName, machineName, job == null ? null : (String) job.get("processName"), jobId,
                    RequestBodies.text(line, "loggedAt"), RequestBodies.optionalLong(line, "seq"),
                    truncate(RequestBodies.trimmedText(line, "source"), 160),
                    RequestBodies.optionalInteger(line, "sessionId"));
        }

        // Yang tidak tertulis: baris cacat, milik mesin lain, terlalu rinci,
        // atau kiriman ulang yang sudah diterima sebelumnya.
        return new LogWriteResponse(true, written, request.lines().size() - written);
    }

    /** Screenshot dari Executor saat job gagal atau melewati batas waktu. */
    @Transactional
    public Map<String, Object> addAttachment(OpenOrchestratorPrincipal agent, String jobIdText, String kind,
                                             String fileName, byte[] content) {
        UUID jobId = Uuids.parseOrNull(jobIdText);
        if (jobId == null) throw ApiException.notFound(JOB_NOT_FOUND);

        Map<String, Object> job = jobRepository.lockById(agent.tenantId(), jobId)
                .orElseThrow(() -> ApiException.notFound(JOB_NOT_FOUND));

        requireOwnJob(agent, job);

        OpenOrchestratorProperties.Agent settings = properties.agent();

        if (content == null || content.length == 0) throw ApiException.badRequest("Berkas kosong.");

        if (content.length > settings.maxAttachmentSize().toBytes()) {
            throw ApiException.tooLarge("Lampiran terlalu besar. Batasnya "
                    + settings.maxAttachmentSize().toMegabytes() + " MB.").withCode("AttachmentTooLarge");
        }

        if (attachmentRepository.countForJob(jobId) >= settings.maxAttachmentsPerJob()) {
            throw ApiException.tooLarge("Satu pekerjaan paling banyak " + settings.maxAttachmentsPerJob()
                    + " lampiran.").withCode("TooManyAttachments");
        }

        String contentType = startsWith(content, PNG_MAGIC) ? "image/png"
                : startsWith(content, JPEG_MAGIC) ? "image/jpeg" : null;

        if (contentType == null) {
            throw ApiException.badRequest("Lampiran harus berupa gambar PNG atau JPEG.").withCode("UnsupportedAttachment");
        }

        UUID id = UUID.randomUUID();
        String name = truncate(fileName == null || fileName.isBlank() ? null : fileName.trim(), 200);

        attachmentRepository.insert(id, agent.tenantId(), jobId, kind == null || kind.isBlank() ? "Screenshot"
                : truncate(kind.trim(), 24), name, contentType, content);

        return Map.of("id", id.toString());
    }

    // -----------------------------------------------------------------
    // Alat
    // -----------------------------------------------------------------

    private static void requireOwnJob(OpenOrchestratorPrincipal agent, Map<String, Object> job) {
        if (!agent.machineId().toString().equals(machineOf(job))
                || ((Number) job.get("contractVersion")).intValue() != 2) {
            throw ApiException.forbidden(NOT_YOUR_JOB).withCode("NotYourJob");
        }
    }

    private static String machineOf(Map<String, Object> job) {
        Object machineId = job.get("machineId");
        return machineId == null ? null : machineId.toString();
    }

    /**
     * Keluaran yang terlalu besar TIDAK menggagalkan laporannya: hasil akhir
     * job lebih penting daripada isinya. Keluarannya dibuang dan dicatat.
     */
    private String acceptableOutput(OpenOrchestratorPrincipal agent, Map<String, Object> job, JobState next,
                                    String outputJson) {
        if (outputJson == null || !next.isFinished()) return null;

        long limit = properties.agent().maxOutputSize().toBytes();
        int size = outputJson.getBytes(StandardCharsets.UTF_8).length;

        if (size <= limit) return outputJson;

        logRepository.insertJobEntry(agent.tenantId(), LogLevel.WARN,
                "Keluaran pekerjaan terlalu besar (" + (size / 1024) + " KB, batas " + (limit / 1024)
                        + " KB) dan tidak disimpan.",
                (String) job.get("robotName"), (String) job.get("processName"),
                Uuids.parseOrNull((String) job.get("id")));

        return null;
    }

    private static Map<String, Object> command(String type, UUID jobId, Integer graceSeconds) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", type);
        command.put("jobId", jobId.toString());
        if (graceSeconds != null) command.put("graceSeconds", graceSeconds);
        return command;
    }

    private static int graceOf(Object value) {
        return value instanceof Number number ? number.intValue() : DEFAULT_STOP_GRACE_SECONDS;
    }

    /** "busy" dan "BUSY" disimpan sama: "Busy". */
    private static String normalizeWord(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = truncate(text.trim(), 24);
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    static String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static String truncateBytes(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_LOG_MESSAGE_BYTES) return text;

        String cut = new String(bytes, 0, MAX_LOG_MESSAGE_BYTES, StandardCharsets.UTF_8);
        // Potongan di tengah karakter multibita menjadi U+FFFD; buang itu.
        return cut.replaceAll("�+$", "") + " …";
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        if (content.length < magic.length) return false;

        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) return false;
        }

        return true;
    }

    private static String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
