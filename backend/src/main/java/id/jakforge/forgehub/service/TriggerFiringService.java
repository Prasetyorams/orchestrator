package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.Cron;
import id.jakforge.forgehub.model.Severity;
import id.jakforge.forgehub.repository.AlertRepository;
import id.jakforge.forgehub.repository.JobRepository;
import id.jakforge.forgehub.repository.LogRepository;
import id.jakforge.forgehub.repository.ProcessRepository;
import id.jakforge.forgehub.repository.TriggerRepository;
import id.jakforge.forgehub.repository.TriggerRepository.DueTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Menjalankan pemicu yang sudah jatuh tempo: menjadwalkan pekerjaannya dan
 * menghitung waktu jalan berikutnya.
 *
 * <p>SATU pemicu per transaksi. Pemicu dikunci, pekerjaannya dibuat, dan waktu
 * jalan berikutnya dicatat dalam transaksi yang sama — pekerjaan yang dibuat
 * tanpa waktu berikutnya tercatat akan dibuat LAGI pada putaran berikutnya.
 * Pemicu yang gagal tidak menahan pemicu lain.
 *
 * <p>Pemicu yang terlewat TIDAK dikejar. Kalau ForgeHub mati semalaman, yang
 * dijalankan saat menyala lagi adalah satu kali, bukan dua belas kali sekaligus
 * menumpuk di robot yang sama.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerFiringService {

    static final String TRIGGER_JOB_SOURCE = "Trigger";
    static final String DEFAULT_PRIORITY = "Normal";
    private static final String ALERT_SOURCE = "triggers";

    private final TriggerRepository triggerRepository;
    private final ProcessRepository processRepository;
    private final JobRepository jobRepository;
    private final LogRepository logRepository;
    private final AlertRepository alertRepository;
    private final NextRunCalculator nextRunCalculator;

    /** Pemicu yang sudah waktunya jalan; masing-masing dijalankan lewat {@link #fireIfDue}. */
    public List<UUID> findDueTriggerIds() {
        return triggerRepository.findDueTriggerIds();
    }

    /**
     * Jalankan satu pemicu kalau MASIH jatuh tempo.
     *
     * <p>Harus dipanggil dari bean lain (lihat {@link TriggerScheduler}), supaya
     * {@code @Transactional}-nya berlaku: kunci baris pemicu hanya bertahan
     * selama transaksinya.
     */
    @Transactional
    public void fireIfDue(UUID triggerId) {
        Optional<DueTrigger> locked = triggerRepository.lockIfDue(triggerId);
        if (locked.isEmpty()) return;

        DueTrigger trigger = locked.get();
        int intervalMinutes = Math.max(NextRunCalculator.MIN_INTERVAL_MINUTES, trigger.intervalMinutes());

        // Proses bisa saja sudah dihapus setelah pemicunya dibuat. Pemicunya
        // dimatikan, bukan diam-diam gagal tiap putaran selamanya — kegagalan
        // yang berulang tanpa henti adalah kegagalan yang berhenti dibaca
        // orang. Yang diperiksa proses di folder PEMICUNYA: yang bernama sama
        // di folder lain adalah proses lain.
        if (!processRepository.existsInFolder(trigger.tenantId(), trigger.processName(), trigger.folderId())) {
            disable(trigger, "Pemicu '" + trigger.name() + "' menunjuk proses '" + trigger.processName()
                    + "' yang sudah tidak ada.");
            return;
        }

        OffsetDateTime nextRunAt = nextRunCalculator.nextRun(trigger.cron(), intervalMinutes,
                Cron.zoneOrUtc(trigger.timezone()));

        if (nextRunAt == null) {
            disable(trigger, "Pemicu '" + trigger.name() + "' memakai ekspresi cron yang tidak pernah cocok: "
                    + trigger.cron());
            return;
        }

        UUID jobId = UUID.randomUUID();

        jobRepository.insert(jobId, trigger.tenantId(), trigger.folderId(), trigger.processName(),
                trigger.robotName(), null, TRIGGER_JOB_SOURCE,
                trigger.priority() == null ? DEFAULT_PRIORITY : trigger.priority(),
                "Dijadwalkan oleh pemicu '" + trigger.name() + "'.", null);

        triggerRepository.recordRun(trigger.id(), nextRunAt);

        logRepository.insertSystemEntry(trigger.tenantId(),
                "Pemicu '" + trigger.name() + "' menjadwalkan " + trigger.processName() + ".",
                trigger.processName(), jobId);

        log.info("Pemicu '{}' menjadwalkan {}; berikutnya {}.", trigger.name(), trigger.processName(), nextRunAt);
    }

    private void disable(DueTrigger trigger, String reason) {
        triggerRepository.disable(trigger.id());

        alertRepository.insert(trigger.tenantId(), Severity.Warning, "Pemicu dimatikan", reason, ALERT_SOURCE);

        log.warn(reason);
    }
}
