package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.model.AgentErrorCodes;
import id.jakforge.openorchestrator.model.LogLevel;
import id.jakforge.openorchestrator.model.RetryPolicy;
import id.jakforge.openorchestrator.model.Severity;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.repository.RobotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Akibat sebuah job Robot Agent selesai: peringatan, tanda pada robot, dan
 * percobaan ulang.
 *
 * <p>Satu tempat untuk semua jalan menuju "selesai" — laporan agent,
 * rekonsiliasi denyut, lease habis, agent hilang — supaya keempatnya
 * menghasilkan akibat yang sama. Aturan yang ditulis di empat tempat akan
 * berbeda di salah satunya.
 *
 * <p>Dipanggil DI DALAM transaksi pemanggilnya; tidak membuka transaksi sendiri.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobOutcomeService {

    private static final String ALERT_SOURCE = "jobs";

    private final JobRepository jobRepository;
    private final RobotRepository robotRepository;
    private final AlertRepository alertRepository;
    private final LogRepository logRepository;

    /** @param job baris job SESUDAH berubah menjadi selesai (kolom camelCase) */
    public void afterFinished(Map<String, Object> job) {
        UUID tenantId = Uuids.parseOrNull((String) job.get("tenantId"));
        UUID robotId = Uuids.parseOrNull((String) job.get("robotId"));
        String state = (String) job.get("state");
        String errorCode = (String) job.get("errorCode");
        String processName = (String) job.get("processName");

        if (tenantId == null) return;

        if ("FAULTED".equals(state)) {
            alertRepository.insert(tenantId, Severity.Error, "Pekerjaan gagal",
                    processName + " gagal" + (errorCode == null ? "" : " (" + errorCode + ")") + ": "
                            + (job.get("info") == null ? "tanpa keterangan" : job.get("info")), ALERT_SOURCE);
        }

        if (robotId != null) {
            if (AgentErrorCodes.LOGON_FAILED.equals(errorCode)) {
                robotRepository.setNeedsAttention(robotId, AgentErrorCodes.LOGON_FAILED);
                alertRepository.insert(tenantId, Severity.Error, "Login Windows robot gagal",
                        "Robot " + job.get("robotName") + " tidak bisa masuk ke Windows. Periksa akun Windows-nya"
                                + " di Tenant › Robots; robot ini tidak diberi job sebelum sandinya diganti.",
                        ALERT_SOURCE);
            }

            robotRepository.markIdleIfFree(robotId);
        }

        retryIfAllowed(job, tenantId, state, errorCode, processName);
    }

    private void retryIfAllowed(Map<String, Object> job, UUID tenantId, String state, String errorCode,
                                String processName) {
        if (!"FAULTED".equals(state)) return;

        int attempt = job.get("attempt") instanceof Number number ? number.intValue() : 1;
        boolean hasRun = job.get("runningAt") != null;
        boolean retried = Boolean.TRUE.equals(job.get("retried"));
        UUID folderId = Uuids.parseOrNull((String) job.get("folderId"));

        if (retried || hasRun || folderId == null || !AgentErrorCodes.RETRYABLE.contains(errorCode)) return;

        int maxRetries = jobRepository.findMaxRetries(tenantId, folderId, processName);
        if (!RetryPolicy.shouldRetry(errorCode, false, attempt, maxRetries, false)) return;

        UUID retryId = UUID.randomUUID();
        String info = "Percobaan ulang ke-" + attempt + " sesudah " + errorCode + ". Menunggu robot yang tersedia.";

        if (!jobRepository.insertRetry(retryId, job, info)) return;

        UUID jobId = Uuids.parseOrNull((String) job.get("id"));

        logRepository.insertJobEntry(tenantId, LogLevel.WARN,
                "Pekerjaan diulang otomatis sesudah " + errorCode + "; percobaan berikutnya " + retryId + ".",
                (String) job.get("robotName"), processName, jobId);

        log.info("Job {} ({}) diulang otomatis sebagai {} sesudah {}.", jobId, processName, retryId, errorCode);
    }
}
