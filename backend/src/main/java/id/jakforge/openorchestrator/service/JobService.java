package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.PageLimits;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.dto.request.CreateJobRequest;
import id.jakforge.openorchestrator.dto.request.UpdateJobStateRequest;
import id.jakforge.openorchestrator.dto.response.CreatedResponse;
import id.jakforge.openorchestrator.dto.response.NextJobResponse;
import id.jakforge.openorchestrator.model.JobState;
import id.jakforge.openorchestrator.model.Severity;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.FolderRepository;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final String JOB_NOT_FOUND = "Pekerjaan tidak ada.";
    private static final String ALERT_SOURCE = "jobs";
    private static final String UNKNOWN_ROBOT = "?";

    private final JobRepository jobRepository;
    private final ProcessService processService;
    private final FolderRepository folderRepository;
    private final LogRepository logRepository;
    private final AlertRepository alertRepository;
    private final FolderAccessService folderAccessService;
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
     * Jadwalkan pekerjaan baru.
     *
     * <p>Proses yang belum diterbitkan DITOLAK di sini, bukan dibiarkan menjadi
     * pekerjaan PENDING yang tidak akan pernah bisa dijalankan robot mana pun —
     * dan yang terlihat kemudian adalah antrean yang menumpuk tanpa sebab.
     *
     * <p>{@code folderId} menyebut folder prosesnya; nama proses unik per
     * folder. Studio tidak mengirimnya — lihat {@link FolderLocations#resolve}.
     */
    @Transactional
    public CreatedResponse create(OpenOrchestratorPrincipal principal, CreateJobRequest request) {
        UUID tenantId = principal.tenantId();
        UUID requestedFolder = folderAccessService.resolveFolderFilter(principal, request.folderId());

        if (request.processName() == null) {
            throw ApiException.badRequest("processName wajib diisi.");
        }

        UUID folder = processService.resolveProcessFolder(tenantId, request.processName(), requestedFolder);

        if (folder == null) {
            throw ApiException.badRequest(requestedFolder == null
                    ? "Proses '" + request.processName() + "' belum diterbitkan ke OpenOrchestrator."
                    : "Proses '" + request.processName() + "' tidak ada di folder ini.");
        }

        UUID jobId = UUID.randomUUID();

        // Folder pekerjaan adalah folder prosesnya, disebut langsung — bukan
        // dibiarkan ditebak basis data dari namanya. Folder tanpa robot berarti
        // pekerjaan ini akan menunggu selamanya, dan keterangannya harus
        // mengatakan itu — bukan "menunggu robot yang tersedia", yang membuat
        // orang menunggu robot yang tidak akan datang.
        boolean robotAvailable = request.robotName() != null || folderRepository.hasRobots(tenantId, folder);

        String info = robotAvailable
                ? "Menunggu robot yang tersedia."
                : "Belum ada robot yang ditugaskan ke folder proses ini. Tugaskan robot lewat Setelan folder.";

        jobRepository.insert(jobId, tenantId, folder, request.processName(), request.robotName(),
                request.machineName(), request.source(), request.priority(), info, request.inputJson());

        logRepository.insertSystemEntry(tenantId,
                "Pekerjaan dijadwalkan untuk " + request.processName() + ".", request.processName(), jobId);

        return CreatedResponse.of(jobId);
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

        return new NextJobResponse(jobRepository.claimNext(principal.tenantId(), robotName).orElse(null));
    }

    @Transactional
    public void updateState(OpenOrchestratorPrincipal principal, String jobIdText, UpdateJobStateRequest request) {
        UUID tenantId = principal.tenantId();
        UUID jobId = parseJobId(jobIdText);

        JobState state = JobState.parse(request.state());

        if (state == null) {
            throw ApiException.badRequest("Keadaan tidak dikenal: '" + request.state() + "'.");
        }

        String processName = jobRepository.findProcessName(tenantId, jobId)
                .orElseThrow(() -> ApiException.notFound(JOB_NOT_FOUND));

        // Yang sudah selesai selalu 100%. Pekerjaan berhasil yang tercatat 40%
        // membuat orang mengira ada yang terhenti di tengah.
        int progress = state.isFinished() ? MAX_PROGRESS : Math.clamp(request.progress(), MIN_PROGRESS, MAX_PROGRESS);

        jobRepository.updateState(tenantId, jobId, state, progress, request.info(), request.outputJson());

        if (state == JobState.FAULTED) {
            alertRepository.insert(tenantId, Severity.Error, "Pekerjaan gagal",
                    processName + " gagal: " + (request.info() == null ? "tanpa keterangan" : request.info()),
                    ALERT_SOURCE);
        }
    }

    public void requestStop(OpenOrchestratorPrincipal principal, String jobId) {
        if (jobRepository.requestStop(principal.tenantId(), parseJobId(jobId)) == 0) {
            throw ApiException.badRequest("Pekerjaan itu tidak sedang menunggu atau berjalan.");
        }
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

            alertRepository.insert(tenantId, Severity.Error, "Pekerjaan terputus",
                    job.get("processName") + " dihentikan karena robot '"
                            + (robotName == null ? UNKNOWN_ROBOT : robotName) + "' tidak lagi terhubung.",
                    ALERT_SOURCE);

            log.warn("Pekerjaan {} ditandai FAULTED: robot {} berhenti berdenyut.", job.get("id"), robotName);
        }

        return faultedJobs.size();
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
