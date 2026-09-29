package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.model.LogLevel;
import id.jakforge.openorchestrator.model.Severity;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keadaan job Robot Agent yang disimpulkan Orchestrator karena agent-nya
 * diam — dijalankan {@link AgentMonitor} tiap beberapa detik.
 *
 * <p>Setiap langkah satu transaksi tersendiri, dipanggil dari kelas lain.
 * Memanggil metode {@code @Transactional} dari kelas yang sama melewati proxy
 * Spring dan berjalan TANPA transaksi — kesalahan yang pernah terjadi di
 * penjadwal pemicu.
 *
 * <p>Semua kegagalan di sini ditandai {@code failure_inferred}: laporan asli
 * agent yang datang belakangan boleh menggantikannya selama belum diulang.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobSupervisionService {

    private static final String ALERT_SOURCE = "jobs";

    private final JobRepository jobRepository;
    private final JobOutcomeService jobOutcomeService;
    private final AlertRepository alertRepository;
    private final LogRepository logRepository;
    private final OpenOrchestratorProperties properties;

    /** Lease penyiapan habis: agent hilang sebelum workflow mulai. Boleh diulang otomatis. */
    @Transactional
    public int expireLeases() {
        List<Map<String, Object>> expired = jobRepository.expireLeases();

        for (Map<String, Object> job : expired) {
            logJob(job, LogLevel.WARN, "Lease penyiapan habis tanpa kabar dari Robot Agent.");
            jobOutcomeService.afterFinished(job);
        }

        return expired.size();
    }

    /** Agent diam saat job berjalan: hilang kontak, BELUM gagal. */
    @Transactional
    public int markUnresponsive() {
        List<Map<String, Object>> silent = jobRepository.markUnresponsive(properties.agent().offlineAfter().toSeconds());

        for (Map<String, Object> job : silent) {
            logJob(job, LogLevel.WARN, "Robot Agent berhenti berdenyut; pekerjaan menunggu kabar.");
        }

        return silent.size();
    }

    /** Terlalu lama hilang kontak: dianggap gagal. */
    @Transactional
    public int markLost() {
        List<Map<String, Object>> lost = jobRepository.markLost(properties.agent().lostAfter().toSeconds());

        for (Map<String, Object> job : lost) {
            long seconds = properties.agent().lostAfter().toSeconds();
            String duration = seconds < 120 ? seconds + " detik" : (seconds / 60) + " menit";

            logJob(job, LogLevel.ERROR, "Robot Agent tidak memberi kabar selama " + duration
                    + "; pekerjaan dianggap gagal.");
            jobOutcomeService.afterFinished(job);
        }

        return lost.size();
    }

    /** Jaring pengaman batas waktu: agent seharusnya sudah menghentikannya sendiri. */
    @Transactional
    public int requestTimeoutStops() {
        List<Map<String, Object>> overdue =
                jobRepository.requestTimeoutStops(properties.agent().timeoutMargin().toSeconds());

        for (Map<String, Object> job : overdue) {
            logJob(job, LogLevel.WARN, "Melewati batas waktu proses; Orchestrator meminta berhenti.");

            UUID tenantId = Uuids.parseOrNull((String) job.get("tenantId"));
            if (tenantId != null) {
                alertRepository.insert(tenantId, Severity.Warning, "Pekerjaan melewati batas waktu",
                        job.get("processName") + " di robot " + job.get("robotName")
                                + " melewati batas waktunya dan tidak dihentikan robotnya; Orchestrator memintanya berhenti.",
                        ALERT_SOURCE);
            }
        }

        return overdue.size();
    }

    private void logJob(Map<String, Object> job, LogLevel level, String message) {
        UUID tenantId = Uuids.parseOrNull((String) job.get("tenantId"));
        if (tenantId == null) return;

        logRepository.insertJobEntry(tenantId, level, message, (String) job.get("robotName"),
                (String) job.get("processName"), Uuids.parseOrNull((String) job.get("id")));

        log.warn("Job {} ({}): {}", job.get("id"), job.get("processName"), message);
    }
}
