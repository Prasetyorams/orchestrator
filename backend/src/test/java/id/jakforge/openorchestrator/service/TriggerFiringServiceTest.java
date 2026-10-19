package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.CalendarRepository;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.repository.ProcessRepository;
import id.jakforge.openorchestrator.repository.TriggerRepository;
import id.jakforge.openorchestrator.repository.TriggerRepository.DueTrigger;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Menjalankan pemicu yang jatuh tempo.
 *
 * <p>Pemicu dan proses palsu; pekerjaan, catatan, dan peringatan ditulis lewat
 * repositori asli ke basis data perekam, supaya yang diuji juga isi SQL-nya.
 */
class TriggerFiringServiceTest {

    /** Minggu, 27 September 2026, 10:00 UTC (17:00 WIB). */
    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final UUID tenantId = UUID.randomUUID();
    private final UUID folderId = UUID.randomUUID();

    private final RecordingDatabase database = new RecordingDatabase();
    private final FakeTriggerRepository triggers = new FakeTriggerRepository();
    private final FakeProcessRepository processes = new FakeProcessRepository();
    private final FakeCalendarRepository calendars = new FakeCalendarRepository();

    private final TriggerFiringService firingService = new TriggerFiringService(triggers, calendars, processes,
            new JobRepository(database), new LogRepository(database), new AlertRepository(database),
            new NextRunCalculator(Clock.fixed(NOW, ZoneOffset.UTC)));

    private DueTrigger dueTrigger(String cron, String priority) {
        return new DueTrigger(UUID.randomUUID(), tenantId, folderId, "Harian", "Tagihan", null, 30, cron, priority,
                "UTC", null, OffsetDateTime.parse("2026-09-27T09:59:00Z"));
    }

    /** Pemicu harian 07:00 dengan kalender, yang jadwalnya {@code slot}. */
    private DueTrigger withCalendar(String cron, int interval, String zone, String slot) {
        return new DueTrigger(UUID.randomUUID(), tenantId, folderId, "Harian", "Tagihan", null, interval, cron, "Normal",
                zone, calendars.id, OffsetDateTime.parse(slot));
    }

    private boolean logMentions(String text) {
        return database.argumentsOf("INSERT INTO logs").stream().anyMatch(a -> String.valueOf(a).contains(text));
    }

    @Test
    @DisplayName("pemicu jatuh tempo menjadwalkan pekerjaan di folder pemicunya dan mencatat jalan berikutnya")
    void dueTriggerSchedulesJob() {
        triggers.locked = Optional.of(dueTrigger(null, null));

        firingService.fireIfDue(triggers.locked.get().id());

        List<Object> jobArgs = database.argumentsOf("INSERT INTO jobs");
        assertEquals(tenantId, jobArgs.get(1));
        assertEquals(folderId, jobArgs.get(2));
        assertEquals("Tagihan", jobArgs.get(3));
        assertNull(jobArgs.get(5), "pemicu tanpa robot: robot mana pun di folder");
        assertEquals("Trigger", jobArgs.get(7));
        assertEquals("Normal", jobArgs.get(8), "prioritas kosong berarti Normal");
        assertEquals("Dijadwalkan oleh pemicu 'Harian'.", jobArgs.get(9));

        assertEquals(List.of(OffsetDateTime.parse("2026-09-27T10:30:00Z")), triggers.recordedNextRuns);
        assertEquals(1, database.statementsContaining("INSERT INTO logs").size());
        assertTrue(triggers.disabledIds.isEmpty());
    }

    @Test
    @DisplayName("pemicu yang prosesnya sudah tidak ada dimatikan, dengan peringatan, tanpa pekerjaan")
    void missingProcessDisablesTrigger() {
        processes.exists = false;
        triggers.locked = Optional.of(dueTrigger(null, "High"));

        firingService.fireIfDue(triggers.locked.get().id());

        assertEquals(List.of(triggers.locked.get().id()), triggers.disabledIds);
        assertEquals("Pemicu 'Harian' menunjuk proses 'Tagihan' yang sudah tidak ada.",
                database.argumentsOf("INSERT INTO alerts").get(3));
        assertTrue(database.statementsContaining("INSERT INTO jobs").isEmpty());
    }

    @Test
    @DisplayName("cron yang tidak pernah cocok mematikan pemicunya")
    void impossibleCronDisablesTrigger() {
        triggers.locked = Optional.of(dueTrigger("0 0 31 2 *", "Normal"));

        firingService.fireIfDue(triggers.locked.get().id());

        assertEquals(1, triggers.disabledIds.size());
        assertTrue(database.statementsContaining("INSERT INTO jobs").isEmpty());
    }

    @Test
    @DisplayName("hari ini hari non-kerja: tanpa pekerjaan, jadwal maju ke hari kerja berikutnya, dicatat sekali")
    void nonWorkingTodaySkips() {
        calendars.dates = Set.of(LocalDate.parse("2026-09-27"));
        triggers.locked = Optional.of(withCalendar("0 7 * * *", 0, "UTC", "2026-09-27T07:00:00Z"));

        firingService.fireIfDue(triggers.locked.get().id());

        assertTrue(database.statementsContaining("INSERT INTO jobs").isEmpty());
        assertTrue(triggers.recordedNextRuns.isEmpty(), "bukan jalan: last_run_at tidak diubah");
        assertEquals(List.of(OffsetDateTime.parse("2026-09-28T07:00:00Z")), triggers.skippedNextRuns);
        assertEquals(1, database.statementsContaining("INSERT INTO logs").size());
        assertTrue(logMentions("dilewati: 2026-09-27 adalah hari non-kerja di kalender 'Libur Nasional'"));
        assertTrue(triggers.disabledIds.isEmpty());
    }

    @Test
    @DisplayName("jadwal di hari libur yang baru dikejar sesudah libur tetap dilewati")
    void overdueHolidaySlotSkips() {
        calendars.dates = Set.of(LocalDate.parse("2026-09-26"));
        triggers.locked = Optional.of(withCalendar("0 7 * * *", 0, "UTC", "2026-09-26T07:00:00Z"));

        firingService.fireIfDue(triggers.locked.get().id());

        assertTrue(database.statementsContaining("INSERT INTO jobs").isEmpty());
        assertEquals(List.of(OffsetDateTime.parse("2026-09-28T07:00:00Z")), triggers.skippedNextRuns);
        assertTrue(logMentions("dilewati: 2026-09-26"));
    }

    @Test
    @DisplayName("hari kerja: pekerjaan dibuat, dan jadwal berikutnya melompati hari libur di depan")
    void workingDayRunsAndNextSkipsHolidays() {
        calendars.dates = Set.of(LocalDate.parse("2026-09-28"), LocalDate.parse("2026-09-29"));
        triggers.locked = Optional.of(withCalendar("0 7 * * *", 0, "UTC", "2026-09-27T07:00:00Z"));

        firingService.fireIfDue(triggers.locked.get().id());

        assertEquals(1, database.statementsContaining("INSERT INTO jobs").size());
        assertEquals(List.of(OffsetDateTime.parse("2026-09-30T07:00:00Z")), triggers.recordedNextRuns);
        assertTrue(triggers.skippedNextRuns.isEmpty());
    }

    @Test
    @DisplayName("tanggal dinilai di zona pemicu: 17:00 WIB tanggal 27 adalah libur; selang mulai lagi 00:00 WIB")
    void intervalTriggerInZoneResumesAtMidnight() {
        calendars.dates = Set.of(LocalDate.parse("2026-09-27"));
        triggers.locked = Optional.of(withCalendar(null, 30, "Asia/Jakarta", "2026-09-27T09:30:00Z"));

        firingService.fireIfDue(triggers.locked.get().id());

        assertTrue(database.statementsContaining("INSERT INTO jobs").isEmpty());
        // 28 September 00:00 WIB = 27 September 17:00 UTC.
        assertEquals(List.of(OffsetDateTime.parse("2026-09-27T17:00:00Z")), triggers.skippedNextRuns);
    }

    @Test
    @DisplayName("pemicu yang sudah diambil penjadwal lain, atau tidak lagi jatuh tempo, dilewati")
    void triggerNoLongerDueIsSkipped() {
        triggers.locked = Optional.empty();

        firingService.fireIfDue(UUID.randomUUID());

        assertTrue(database.statements.isEmpty());
        assertTrue(triggers.recordedNextRuns.isEmpty());
    }

    private static final class FakeTriggerRepository extends TriggerRepository {

        Optional<DueTrigger> locked = Optional.empty();
        final List<OffsetDateTime> recordedNextRuns = new ArrayList<>();
        final List<OffsetDateTime> skippedNextRuns = new ArrayList<>();
        final List<UUID> disabledIds = new ArrayList<>();

        FakeTriggerRepository() {
            super(null);
        }

        @Override
        public Optional<DueTrigger> lockIfDue(UUID triggerId) {
            return locked;
        }

        @Override
        public void recordRun(UUID triggerId, OffsetDateTime nextRunAt) {
            recordedNextRuns.add(nextRunAt);
        }

        @Override
        public void recordSkip(UUID triggerId, OffsetDateTime nextRunAt) {
            skippedNextRuns.add(nextRunAt);
        }

        @Override
        public void disable(UUID triggerId) {
            disabledIds.add(triggerId);
        }
    }

    private static final class FakeCalendarRepository extends CalendarRepository {

        final UUID id = UUID.randomUUID();
        Set<LocalDate> dates = Set.of();

        FakeCalendarRepository() {
            super(null);
        }

        @Override
        public Set<LocalDate> findDates(UUID calendarId) {
            return calendarId == null ? Set.of() : dates;
        }

        @Override
        public Optional<String> findName(UUID tenantId, UUID calendarId) {
            return Optional.of("Libur Nasional");
        }
    }

    private static final class FakeProcessRepository extends ProcessRepository {

        boolean exists = true;

        FakeProcessRepository() {
            super(null);
        }

        @Override
        public boolean existsInFolder(UUID tenantId, String name, UUID folderId) {
            return exists;
        }
    }
}
