package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.Cron;
import id.jakforge.openorchestrator.model.JobPriorities;
import id.jakforge.openorchestrator.model.Severity;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.CalendarRepository;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.repository.ProcessRepository;
import id.jakforge.openorchestrator.repository.TriggerRepository;
import id.jakforge.openorchestrator.repository.TriggerRepository.DueTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * <p>Pemicu yang terlewat TIDAK dikejar. Kalau OpenOrchestrator mati semalaman, yang
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
    private static final DateTimeFormatter NEXT_RUN_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm VV");

    private final TriggerRepository triggerRepository;
    private final CalendarRepository calendarRepository;
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

        ZoneId zone = Cron.zoneOrUtc(trigger.timezone());
        Set<LocalDate> nonWorkingDays = calendarRepository.findDates(trigger.calendarId());

        OffsetDateTime nextRunAt = nextRunCalculator.nextRun(trigger.cron(), intervalMinutes, zone, nonWorkingDays);

        if (nextRunAt == null) {
            disable(trigger, nonWorkingDays.isEmpty()
                    ? "Pemicu '" + trigger.name() + "' memakai ekspresi cron yang tidak pernah cocok: " + trigger.cron()
                    : "Pemicu '" + trigger.name() + "' tidak punya waktu jalan lagi: cron-nya tidak pernah cocok,"
                            + " atau semua tanggal yang cocok adalah hari non-kerja di kalendernya.");
            return;
        }

        // Hari non-kerja (V16): tidak ada pekerjaan yang dibuat bila HARI INI
        // atau hari JADWALNYA ada di kalender pemicu — keduanya, karena jadwal
        // 23:59 yang baru diambil penjadwal pukul 00:00 di hari libur, dan
        // jadwal hari libur yang baru dikejar sesudah OpenOrchestrator mati
        // semalaman, sama-sama bukan jalan yang dimaksud. Waktu berikutnya
        // sudah melompati hari libur, jadi ini dicatat sekali per hari libur.
        OffsetDateTime now = nextRunCalculator.now();
        LocalDate skippedDay = NextRunCalculator.isNonWorkingDay(now, zone, nonWorkingDays)
                ? now.atZoneSameInstant(zone).toLocalDate()
                : NextRunCalculator.isNonWorkingDay(trigger.nextRunAt(), zone, nonWorkingDays)
                        ? trigger.nextRunAt().atZoneSameInstant(zone).toLocalDate()
                        : null;

        if (skippedDay != null) {
            triggerRepository.recordSkip(trigger.id(), nextRunAt);

            String calendarName = calendarRepository.findName(trigger.tenantId(), trigger.calendarId()).orElse("?");
            String next = nextRunAt.atZoneSameInstant(zone).format(NEXT_RUN_FORMAT);

            logRepository.insertSystemEntry(trigger.tenantId(),
                    "Pemicu '" + trigger.name() + "' dilewati: " + skippedDay + " adalah hari non-kerja di kalender '"
                            + calendarName + "'. Berikutnya " + next + ".",
                    trigger.processName(), null);
            log.info("Pemicu '{}' dilewati: {} hari non-kerja; berikutnya {}.", trigger.name(), skippedDay, nextRunAt);
            return;
        }

        UUID jobId = UUID.randomUUID();

        // Prioritas dibakukan ("high" menjadi "High"): pengurutan klaim
        // membandingkan bentuk baku, dan pemicu tidak boleh gagal karena ejaan.
        jobRepository.insert(new JobRepository.NewJob(jobId, trigger.tenantId(), trigger.folderId(),
                trigger.processName(), trigger.robotName(), null, null, TRIGGER_JOB_SOURCE,
                trigger.priority() == null ? DEFAULT_PRIORITY : JobPriorities.storedOrNormal(trigger.priority()),
                "Dijadwalkan oleh pemicu '" + trigger.name() + "'.", null, null));

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
