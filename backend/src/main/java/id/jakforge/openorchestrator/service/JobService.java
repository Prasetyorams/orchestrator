package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.PageLimits;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.dto.request.CreateJobRequest;
import id.jakforge.openorchestrator.dto.request.UpdateJobStateRequest;
import id.jakforge.openorchestrator.dto.response.JobsCreatedResponse;
import id.jakforge.openorchestrator.dto.response.NextJobResponse;
import id.jakforge.openorchestrator.model.FileContent;
import id.jakforge.openorchestrator.model.JobCommands;
import id.jakforge.openorchestrator.model.JobPriorities;
import id.jakforge.openorchestrator.model.JobState;
import id.jakforge.openorchestrator.model.JobTransitions;
import id.jakforge.openorchestrator.model.LogLevel;
import id.jakforge.openorchestrator.model.RuntimeTypes;
import id.jakforge.openorchestrator.model.Severity;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.AttachmentRepository;
import id.jakforge.openorchestrator.repository.FolderRepository;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aturan tentang pekerjaan.
 *
 * <p>Yang ada di sini adalah keputusan, bukan SQL dan bukan HTTP: proses yang
 * belum diterbitkan tidak boleh dijadwalkan, keadaan yang tidak dikenal
 * ditolak, kegagalan menghasilkan peringatan.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    static final int DEFAULT_PAGE_SIZE = 100;
    static final int MAX_PAGE_SIZE = 1000;

    /**
     * Pekerjaan dianggap terputus sesudah robotnya diam selama DUA kali batas
     * putus robot, bukan satu kali: robot yang baru saja melewatkan satu denyut
     * belum tentu mati, dan menandai pekerjaannya gagal terlalu cepat
     * menghasilkan kegagalan palsu untuk automasi yang sebenarnya masih berjalan.
     */
    static final int DISCONNECTED_JOB_TIMEOUT_MULTIPLIER = 2;

    static final int MIN_PROGRESS = 0;
    static final int MAX_PROGRESS = 100;

    /**
     * Paling banyak berapa job dari satu permintaan ("Execute the process N
     * times"). Salah ketik 1000 untuk 10 berarti seribu job di antrean robot.
     */
    static final int MAX_JOBS_PER_REQUEST = 100;

    /** Sumber job hasil Jalankan Ulang. */
    static final String RESTART_SOURCE = "Restart";

    private static final String JOB_NOT_FOUND = "Pekerjaan tidak ada.";
    private static final String ALERT_SOURCE = "jobs";
    private static final String UNKNOWN_ROBOT = "?";

    private final JobRepository jobRepository;
    private final ProcessService processService;
    private final FolderRepository folderRepository;
    private final LogRepository logRepository;
    private final AlertRepository alertRepository;
    private final AttachmentRepository attachmentRepository;
    private final FolderAccessService folderAccessService;
    private final StartJobOptionsService startJobOptionsService;
    private final OpenOrchestratorProperties properties;

    /** Tanpa {@code folderId}: pekerjaan seluruh penyewa. */
    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal, String state, String processName,
                                             String folderId, Integer limit) {
        UUID folder = folderAccessService.resolveFolderFilter(principal, folderId);

        // Keadaan dinormalkan lewat enum, bukan dengan toUpperCase mentah:
        // "?state=berjalan" tidak akan pernah cocok, dan lebih baik menyaring
        // dengan nilai yang jelas tidak ada daripada dengan untai sembarang.
        JobState jobState = JobState.parse(state);

        return jobRepository.search(principal.tenantId(), jobState == null ? null : jobState.name(), processName,
                folder, PageLimits.clamp(limit, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE));
    }

    public Map<String, Object> findById(OpenOrchestratorPrincipal principal, String jobId) {
        return jobRepository.findById(principal.tenantId(), parseJobId(jobId))
                .orElseThrow(() -> ApiException.notFound(JOB_NOT_FOUND));
    }

    /**
     * Jadwalkan pekerjaan baru — satu, atau {@code count} sekaligus ("Execute
     * the process N times").
     *
     * <p>Proses yang belum diterbitkan DITOLAK di sini, bukan dibiarkan menjadi
     * pekerjaan PENDING yang tidak akan pernah bisa dijalankan robot mana pun —
     * dan yang terlihat kemudian adalah antrean yang menumpuk tanpa sebab.
     * Alasan yang sama untuk tipe runtime, mesin, dan robot yang diminta:
     * kombinasi yang tidak dimiliki folder itu ditolak, lihat
     * {@link StartJobOptionsService#validate}. Permintaan lama yang hanya
     * menyebut robot (Studio) tidak diperiksa — robotnya boleh saja baru
     * menyambung nanti.
     *
     * <p>{@code folderId} menyebut folder prosesnya; nama proses unik per
     * folder. Studio tidak mengirimnya — lihat {@link FolderLocations#resolve}.
     */
    @Transactional
    public JobsCreatedResponse create(OpenOrchestratorPrincipal principal, CreateJobRequest request) {
        UUID tenantId = principal.tenantId();
        UUID requestedFolder = folderAccessService.resolveFolderFilter(principal, request.folderId());

        if (request.processName() == null) {
            throw ApiException.badRequest("processName wajib diisi.");
        }

        int count = request.count() == null ? 1 : request.count();

        if (request.countMalformed() || count < 1 || count > MAX_JOBS_PER_REQUEST) {
            throw ApiException.badRequest("Jumlah jalan harus bilangan bulat 1 sampai " + MAX_JOBS_PER_REQUEST + ".");
        }

        String runtimeType = null;

        if (!RuntimeTypes.isBlank(request.runtimeType())) {
            runtimeType = RuntimeTypes.parse(request.runtimeType()).orElseThrow(() -> ApiException.badRequest(
                    "Tipe runtime tidak dikenal: '" + request.runtimeType() + "'. Pilih Production, Testing, atau Development."));
        }

        String priority = JobPriorities.parse(request.priority() == null ? JobPriorities.INHERITED : request.priority())
                .orElseThrow(() -> ApiException.badRequest("Prioritas tidak dikenal: '" + request.priority()
                        + "'. Pilih Low, Normal, High, atau Inherited."));

        UUID folder = processService.resolveProcessFolder(tenantId, request.processName(), requestedFolder);

        if (folder == null) {
            throw ApiException.badRequest(requestedFolder == null
                    ? "Proses '" + request.processName() + "' belum diterbitkan ke OpenOrchestrator."
                    : "Proses '" + request.processName() + "' tidak ada di folder ini.");
        }

        if (runtimeType != null || request.machineName() != null) {
            startJobOptionsService.validate(startJobOptionsService.forFolder(principal, folder), runtimeType,
                    request.machineName(), request.robotName());
        }

        if (JobPriorities.INHERITED.equals(priority)) {
            priority = processService.findPriority(tenantId, folder, request.processName());
        }

        // Folder pekerjaan adalah folder prosesnya, disebut langsung — bukan
        // dibiarkan ditebak basis data dari namanya. Folder tanpa robot berarti
        // pekerjaan ini akan menunggu selamanya, dan keterangannya harus
        // mengatakan itu — bukan "menunggu robot yang tersedia", yang membuat
        // orang menunggu robot yang tidak akan datang.
        boolean robotAvailable = request.robotName() != null || folderRepository.hasRobots(tenantId, folder);

        String info = robotAvailable
                ? "Menunggu robot yang tersedia."
                : "Belum ada robot yang ditugaskan ke folder proses ini. Tugaskan robot lewat Setelan folder.";

        List<UUID> jobIds = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            UUID jobId = UUID.randomUUID();

            jobRepository.insert(new JobRepository.NewJob(jobId, tenantId, folder, request.processName(),
                    request.robotName(), request.machineName(), runtimeType, request.source(), priority, info,
                    request.inputJson(), null));

            logRepository.insertSystemEntry(tenantId,
                    "Pekerjaan dijadwalkan untuk " + request.processName() + ".", request.processName(), jobId);

            jobIds.add(jobId);
        }

        return JobsCreatedResponse.of(jobIds);
    }

    /**
     * Pilihan Execution settings Start Job untuk sebuah folder: tipe runtime,
     * akun, dan mesin yang benar-benar bisa menjalankan job di sana.
     */
    public StartJobOptionsService.StartOptions startOptions(OpenOrchestratorPrincipal principal, String folderId) {
        UUID folder = folderAccessService.resolveFolderFilter(principal, folderId);
        if (folder == null) throw ApiException.badRequest("folderId wajib diisi.");

        return startJobOptionsService.forFolder(principal, folder);
    }

    /**
     * Jalankan ulang: job BARU dengan konfigurasi job lama — proses, robot dan
     * mesin yang diminta, tipe runtime, prioritas, dan argumen masukannya. Job
     * lama tidak diubah; ia tetap tercatat sebagai riwayat, dan job baru
     * menunjuknya lewat {@code restartedFrom}.
     *
     * <p>Job yang masih menunggu ditolak: belum ada yang perlu diulang, dan
     * "mengulangnya" hanya menggandakan antrean.
     */
    @Transactional
    public JobsCreatedResponse restart(OpenOrchestratorPrincipal principal, String jobIdText) {
        UUID tenantId = principal.tenantId();
        UUID jobId = parseJobId(jobIdText);
        Map<String, Object> job = requireAccessibleJob(principal, jobId);
        JobState state = JobState.valueOf((String) job.get("state"));

        if (state == JobState.PENDING) {
            throw ApiException.conflict("Pekerjaan ini belum berjalan, jadi belum ada yang perlu dijalankan ulang.")
                    .withState(state.name());
        }

        String processName = (String) job.get("processName");
        UUID folder = Uuids.parseOrNull((String) job.get("folderId"));

        if (folder == null || !folder.equals(processService.resolveProcessFolder(tenantId, processName, folder))) {
            throw ApiException.badRequest("Proses '" + processName + "' sudah tidak ada di folder ini.");
        }

        UUID newJobId = UUID.randomUUID();

        jobRepository.insert(new JobRepository.NewJob(newJobId, tenantId, folder, processName,
                (String) job.get("targetRobotName"), (String) job.get("targetMachineName"),
                (String) job.get("runtimeType"), RESTART_SOURCE, (String) job.get("priority"),
                "Dijalankan ulang dari pekerjaan " + jobId + ". Menunggu robot yang tersedia.",
                (String) job.get("inputJson"), jobId));

        logRepository.insertSystemEntry(tenantId, "Pekerjaan dijalankan ulang dari " + jobId + ".", processName,
                newJobId);

        return JobsCreatedResponse.of(List.of(newJobId));
    }

    /**
     * Robot mengambil pekerjaan berikutnya.
     *
     * <p>Selalu berhasil, bahkan ketika tidak ada apa-apa: {@code {"job": null}}
     * adalah jawaban yang benar dan yang paling sering. Robot yang menerima 404
     * setiap beberapa detik akan memenuhi catatannya dengan galat yang bukan
     * galat.
     */
    @Transactional
    public NextJobResponse claimNext(OpenOrchestratorPrincipal principal, String robotName) {
        if (robotName == null || robotName.isBlank()) {
            throw ApiException.badRequest("Parameter 'robot' wajib diisi.");
        }

        Map<String, Object> job = jobRepository.claimNext(principal.tenantId(), robotName).orElse(null);
        if (job == null) return new NextJobResponse(null);

        // Kemampuan jobs.next.package: robot langsung tahu paket, versi, titik
        // masuk, dan sidiknya — tanpa izin tambahan untuk membaca daftar proses
        // dan paket, dan tanpa kemungkinan membaca versi yang sudah berganti.
        UUID jobId = Uuids.parseOrNull((String) job.get("id"));
        UUID folderId = Uuids.parseOrNull((String) job.get("folderId"));
        Map<String, Object> plan = jobRepository.findExecutionPlan(principal.tenantId(), folderId,
                (String) job.get("processName")).orElseGet(HashMap::new);

        job.put("packageName", plan.get("packageName"));
        job.put("packageVersion", plan.get("packageVersion"));
        job.put("entryPoint", plan.get("entryPoint"));
        job.put("packageSha256", plan.get("sha256"));

        jobRepository.recordExecutionPlan(jobId, (String) plan.get("packageName"), (String) plan.get("packageVersion"),
                (String) plan.get("sha256"), null, null);

        return new NextJobResponse(job);
    }

    /**
     * Laporan keadaan dari robot v1 (JakRunner, atau agent yang masuk dengan
     * akun pengguna).
     *
     * <p>Dua penjagaan ({@code jobs.state.guard}, diminta sisi robot): RUNNING
     * sesudah STOPPING ditolak 409, dan job yang sudah selesai tidak berubah
     * lagi — KECUALI kegagalannya kesimpulan server (robot sempat diam), yang
     * boleh digantikan laporan asli robotnya. Laporan akhir yang ditolak tetap
     * dicatat di log job, jadi transaksinya tidak dibatalkan oleh 409 itu.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void updateState(OpenOrchestratorPrincipal principal, String jobIdText, UpdateJobStateRequest request) {
        UUID tenantId = principal.tenantId();
        UUID jobId = parseJobId(jobIdText);

        JobState state = JobState.parse(request.state());

        if (state == null || !JobState.V1_STATES.contains(state)) {
            throw ApiException.badRequest("Keadaan tidak dikenal: '" + request.state() + "'.");
        }

        Map<String, Object> job = jobRepository.lockById(tenantId, jobId)
                .orElseThrow(() -> ApiException.notFound(JOB_NOT_FOUND));

        String processName = (String) job.get("processName");
        JobState current = JobState.valueOf((String) job.get("state"));

        if (((Number) job.get("contractVersion")).intValue() == 2) {
            throw ApiException.conflict("Pekerjaan ini dijalankan Robot Agent; laporannya lewat /api/agent.")
                    .withCode("AgentJob").withState(current.name());
        }

        var snapshot = new JobTransitions.Snapshot(current, Boolean.TRUE.equals(job.get("failureInferred")),
                Boolean.TRUE.equals(job.get("retried")), job.get("stopRequestedAt") != null, 0);

        JobTransitions.Decision decision = JobTransitions.forV1(snapshot, state);

        if (decision.verdict() == JobTransitions.Verdict.REJECT) {
            if (decision.lateFinal()) {
                logRepository.insertJobEntry(tenantId, LogLevel.WARN,
                        "Laporan terlambat dari robot: " + state
                                + (request.info() == null ? "" : " — " + request.info())
                                + ". Pekerjaan ini sudah " + current + " dan tidak diubah.",
                        (String) job.get("robotName"), processName, jobId);
            }

            throw ApiException.conflict("Pekerjaan ini sudah " + current + "; laporan " + state + " ditolak.")
                    .withCode("InvalidTransition").withState(current.name());
        }

        JobState next = decision.state();

        // Yang sudah selesai selalu 100%. Pekerjaan berhasil yang tercatat 40%
        // membuat orang mengira ada yang terhenti di tengah.
        int progress = next.isFinished() ? MAX_PROGRESS : Math.clamp(request.progress(), MIN_PROGRESS, MAX_PROGRESS);

        jobRepository.updateState(tenantId, jobId, next, progress, request.info(), request.outputJson(),
                decision.revived());

        if (next == JobState.FAULTED) {
            alertRepository.insert(tenantId, Severity.Error, "Pekerjaan gagal",
                    processName + " gagal: " + (request.info() == null ? "tanpa keterangan" : request.info()),
                    ALERT_SOURCE);
        }
    }

    /** Lampiran job (screenshot dari Robot Agent), tanpa isinya. */
    public List<Map<String, Object>> findAttachments(OpenOrchestratorPrincipal principal, String jobId) {
        return attachmentRepository.findForJob(principal.tenantId(), parseJobId(jobId));
    }

    public FileContent getAttachment(OpenOrchestratorPrincipal principal, String jobId, String attachmentId) {
        UUID id = Uuids.parseOrNull(attachmentId);
        if (id == null) throw ApiException.notFound("Lampiran tidak ada.");

        return attachmentRepository.findContent(principal.tenantId(), parseJobId(jobId), id)
                .orElseThrow(() -> ApiException.notFound("Lampiran tidak ada."));
    }

    /**
     * Minta berhenti. Robot v1 menerimanya di jawaban denyut berikutnya
     * (StopJob); Robot Agent juga, lalu KillJob kalau jeda berhenti rapinya lewat.
     */
    public void requestStop(OpenOrchestratorPrincipal principal, String jobIdText) {
        UUID jobId = parseJobId(jobIdText);

        // Job di folder yang tidak boleh dibuka orang itu tidak boleh ia
        // hentikan. Id yang tidak ada tetap dijawab seperti sebelumnya.
        jobRepository.findById(principal.tenantId(), jobId).ifPresent(job -> requireJobFolder(principal, job));

        if (jobRepository.requestStop(principal.tenantId(), jobId) == 0) {
            throw ApiException.badRequest("Pekerjaan itu tidak sedang menunggu atau berjalan.");
        }
    }

    /**
     * Minta dimatikan paksa (Kill): robot menerima KillJob tanpa menunggu jeda
     * berhenti rapi. Robot v1 menerimanya BERSAMA StopJob, jadi robot yang
     * belum mengenal KillJob tetap berhenti rapi.
     *
     * <p>Job yang masih menunggu belum punya proses untuk dimatikan — cukup
     * dihentikan.
     */
    @Transactional
    public void requestKill(OpenOrchestratorPrincipal principal, String jobIdText) {
        UUID tenantId = principal.tenantId();
        UUID jobId = parseJobId(jobIdText);
        Map<String, Object> job = requireAccessibleJob(principal, jobId);
        JobState state = JobState.valueOf((String) job.get("state"));

        if (state == JobState.PENDING) {
            throw ApiException.conflict("Pekerjaan ini belum diambil robot, jadi tidak ada yang perlu dimatikan — "
                    + "pakai Hentikan untuk membatalkannya.").withState(state.name());
        }

        if (state.isFinished() || jobRepository.requestKill(tenantId, jobId) == 0) {
            throw ApiException.conflict("Pekerjaan ini sudah selesai (" + state + ").").withState(state.name());
        }

        logRepository.insertJobEntry(tenantId, LogLevel.WARN, "Diminta dimatikan paksa oleh " + principal.username() + ".",
                (String) job.get("robotName"), (String) job.get("processName"), jobId);
    }

    /**
     * Minta jeda. Job tetap RUNNING dan robotnya tetap sibuk: robot yang
     * dijeda masih memegang mouse dan papan ketik, dan tidak boleh diberi job
     * lain. Robot menerima PauseJob pada denyut berikutnya, lalu menahan
     * workflow sebelum activity berikutnya.
     */
    @Transactional
    public void requestPause(OpenOrchestratorPrincipal principal, String jobIdText) {
        UUID tenantId = principal.tenantId();
        UUID jobId = parseJobId(jobIdText);
        Map<String, Object> job = requireAccessibleJob(principal, jobId);
        JobState state = JobState.valueOf((String) job.get("state"));

        if (state != JobState.RUNNING) {
            throw ApiException.conflict("Hanya pekerjaan yang sedang berjalan yang bisa dijeda.").withState(state.name());
        }

        // Sudah diminta: permintaan kedua tidak mengubah apa pun, dan tidak
        // perlu dijawab sebagai kesalahan.
        if (Boolean.TRUE.equals(job.get("pauseRequested"))) return;

        if (jobRepository.requestPause(tenantId, jobId) == 0) {
            throw ApiException.conflict("Hanya pekerjaan yang sedang berjalan yang bisa dijeda.");
        }

        logRepository.insertJobEntry(tenantId, LogLevel.INFO, "Jeda diminta dari dasbor oleh " + principal.username() + ".",
                (String) job.get("robotName"), (String) job.get("processName"), jobId);
    }

    /**
     * Lanjutkan job yang dijeda dari dasbor. Yang dijeda langsung di PC robot
     * hanya dilanjutkan di sana — orang di depan PC itu yang memutuskan.
     *
     * <p>Job yang sudah berhenti tidak bisa dilanjutkan dari tengah: prosesnya
     * sudah tidak ada. Yang bisa hanyalah Jalankan Ulang, dari awal.
     */
    @Transactional
    public void requestResume(OpenOrchestratorPrincipal principal, String jobIdText) {
        UUID tenantId = principal.tenantId();
        UUID jobId = parseJobId(jobIdText);
        Map<String, Object> job = requireAccessibleJob(principal, jobId);
        JobState state = JobState.valueOf((String) job.get("state"));

        if (state.isFinished()) {
            throw ApiException.conflict("Pekerjaan yang sudah selesai tidak bisa dilanjutkan dari tengah — "
                    + "jalankan ulang untuk memulainya dari awal.").withState(state.name());
        }

        if (state != JobState.RUNNING) {
            throw ApiException.conflict("Pekerjaan ini tidak sedang dijeda.").withState(state.name());
        }

        boolean paused = Boolean.TRUE.equals(job.get("paused"));

        if (!Boolean.TRUE.equals(job.get("pauseRequested"))) {
            if (paused && JobCommands.SOURCE_LOCAL.equals(job.get("pauseSource"))) {
                throw ApiException.conflict("Pekerjaan ini dijeda langsung di PC robot, jadi hanya bisa dilanjutkan dari sana.");
            }

            if (!paused) throw ApiException.conflict("Pekerjaan ini tidak sedang dijeda.");

            // Lanjut sudah diminta; robot menerima ResumeJob pada denyut berikutnya.
            return;
        }

        jobRepository.cancelPause(tenantId, jobId);

        logRepository.insertJobEntry(tenantId, LogLevel.INFO,
                (paused ? "Diminta dilanjutkan dari dasbor oleh " : "Permintaan jeda dibatalkan oleh ")
                        + principal.username() + ".",
                (String) job.get("robotName"), (String) job.get("processName"), jobId);
    }

    public void delete(OpenOrchestratorPrincipal principal, String jobId) {
        if (jobRepository.delete(principal.tenantId(), parseJobId(jobId)) == 0) {
            throw ApiException.notFound(JOB_NOT_FOUND);
        }
    }

    /**
     * Pekerjaan yang robotnya menghilang — dijalankan penjadwal.
     *
     * <p>Robot yang mati di tengah jalan tidak pernah melaporkan hasil akhir,
     * jadi pekerjaannya akan berstatus RUNNING selamanya — dan kartu "berjalan"
     * di dasbor terus menghitungnya.
     *
     * @return jumlah pekerjaan yang ditandai gagal
     */
    @Transactional
    public int failJobsOfDisconnectedRobots() {
        int silenceSeconds = Math.toIntExact(
                properties.robot().heartbeatTimeout().toSeconds() * DISCONNECTED_JOB_TIMEOUT_MULTIPLIER);

        List<Map<String, Object>> faultedJobs = jobRepository.markJobsOfSilentRobotsFaulted(silenceSeconds);

        for (Map<String, Object> job : faultedJobs) {
            UUID tenantId = Uuids.parseOrNull((String) job.get("tenantId"));
            Object robotName = job.get("robotName");

            // Yang sedang dihentikan menjadi STOPPED: memang itu yang diminta,
            // jadi bukan peringatan — cukup dicatat di log job-nya.
            if (JobState.STOPPED.name().equals(job.get("state"))) {
                logRepository.insertJobEntry(tenantId, LogLevel.WARN,
                        "Robot berhenti berdenyut saat pekerjaan sedang dihentikan; pekerjaan dianggap berhenti.",
                        robotName == null ? null : String.valueOf(robotName), (String) job.get("processName"),
                        Uuids.parseOrNull((String) job.get("id")));
                continue;
            }

            alertRepository.insert(tenantId, Severity.Error, "Pekerjaan terputus",
                    job.get("processName") + " dihentikan karena robot '"
                            + (robotName == null ? UNKNOWN_ROBOT : robotName) + "' tidak lagi terhubung.",
                    ALERT_SOURCE);

            log.warn("Pekerjaan {} ditandai FAULTED: robot {} berhenti berdenyut.", job.get("id"), robotName);
        }

        return faultedJobs.size();
    }

    /**
     * Job itu, kalau ada DAN foldernya boleh dibuka orang ini. Aksi dari
     * dasbor — hentikan, matikan, jeda, jalankan ulang — tidak boleh menyentuh
     * job di folder yang tidak bisa ia lihat.
     */
    private Map<String, Object> requireAccessibleJob(OpenOrchestratorPrincipal principal, UUID jobId) {
        Map<String, Object> job = jobRepository.findById(principal.tenantId(), jobId)
                .orElseThrow(() -> ApiException.notFound(JOB_NOT_FOUND));

        requireJobFolder(principal, job);

        return job;
    }

    private void requireJobFolder(OpenOrchestratorPrincipal principal, Map<String, Object> job) {
        UUID folderId = Uuids.parseOrNull((String) job.get("folderId"));
        if (folderId != null) folderAccessService.requireAccessibleFolder(principal, folderId);
    }

    /**
     * Id dari URL menjadi UUID.
     *
     * <p>Yang bukan UUID dijawab 404, bukan 500: itu permintaan yang salah
     * bentuk, dan 500 mengarahkan orang mencari kerusakan di server.
     */
    private static UUID parseJobId(String jobId) {
        UUID id = Uuids.parseOrNull(jobId);
        if (id == null) throw ApiException.notFound(JOB_NOT_FOUND);
        return id;
    }
}
