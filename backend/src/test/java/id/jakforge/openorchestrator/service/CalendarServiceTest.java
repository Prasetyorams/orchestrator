package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.dto.request.SaveCalendarRequest;
import id.jakforge.openorchestrator.dto.request.SaveCalendarRequest.Day;
import id.jakforge.openorchestrator.repository.CalendarRepository;
import id.jakforge.openorchestrator.repository.CalendarRepository.CalendarDay;
import id.jakforge.openorchestrator.repository.TriggerRepository;
import id.jakforge.openorchestrator.repository.TriggerRepository.CalendarTrigger;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kalender hari non-kerja (V16): isian, penghapusan, dan hitung ulang pemicu. */
class CalendarServiceTest {

    /** Minggu, 27 September 2026, 10:00 UTC. */
    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final OpenOrchestratorPrincipal principal =
            new OpenOrchestratorPrincipal(UUID.randomUUID(), UUID.randomUUID(), "fajar", "Administrator");

    private final FakeCalendars calendars = new FakeCalendars();
    private final FakeTriggers triggers = new FakeTriggers();
    private final CalendarService service = new CalendarService(calendars, triggers,
            new NextRunCalculator(Clock.fixed(NOW, ZoneOffset.UTC)));

    private static SaveCalendarRequest request(String name, Day... days) {
        return new SaveCalendarRequest(name, null, List.of(days));
    }

    @Test
    @DisplayName("tanggal disimpan urut; tanggal kembar digabung dan catatan yang terisi menang")
    void daysSortedAndMerged() {
        service.create(principal, request("Libur Nasional",
                new Day("2026-12-25", "Natal"), new Day("2026-08-17", null), new Day(" 2026-08-17 ", "HUT RI")));

        assertEquals(List.of(new CalendarDay(LocalDate.parse("2026-08-17"), "HUT RI"),
                new CalendarDay(LocalDate.parse("2026-12-25"), "Natal")), calendars.savedDays);
        assertEquals("Libur Nasional", calendars.insertedName);
    }

    @Test
    @DisplayName("tanggal yang tidak sah ditolak 400 dengan contoh bentuknya")
    void invalidDateRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.create(principal, request("Libur", new Day("25-12-2026", null))));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertEquals("Tanggal tidak sah: '25-12-2026'. Bentuknya tahun-bulan-tanggal, mis. 2026-12-25.", error.getMessage());
        assertNull(calendars.insertedName, "tidak ada yang disimpan");
    }

    @Test
    @DisplayName("nama yang sudah dipakai ditolak 409")
    void duplicateNameRejected() {
        calendars.nameTaken = true;

        ApiException error = assertThrows(ApiException.class, () -> service.create(principal, request("Libur")));

        assertEquals(HttpStatus.CONFLICT, error.status());
    }

    @Test
    @DisplayName("kalender yang masih dipakai pemicu tidak bisa dihapus: 409 menyebut pemicunya")
    void deleteInUseRejected() {
        calendars.triggerNames = List.of("Keuangan / Rekap Harian", "Shared / Tagihan");

        ApiException error = assertThrows(ApiException.class,
                () -> service.delete(principal, UUID.randomUUID().toString()));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("CALENDAR_IN_USE", error.errorCode());
        assertTrue(error.getMessage().contains("Keuangan / Rekap Harian, Shared / Tagihan"));
        assertEquals(0, calendars.deleted);
    }

    @Test
    @DisplayName("mengubah tanggal menghitung ulang pemicu cron; selang hanya kalau jadwalnya kini jatuh di libur")
    void updateReschedulesTriggers() {
        UUID cron = UUID.randomUUID();
        UUID intervalOnHoliday = UUID.randomUUID();
        UUID intervalOnWorkingDay = UUID.randomUUID();
        triggers.onCalendar = List.of(
                new CalendarTrigger(cron, "0 7 * * *", 0, "UTC", OffsetDateTime.parse("2026-09-28T07:00Z")),
                new CalendarTrigger(intervalOnHoliday, null, 60, "UTC", OffsetDateTime.parse("2026-09-28T03:00Z")),
                new CalendarTrigger(intervalOnWorkingDay, null, 60, "UTC", OffsetDateTime.parse("2026-09-27T11:00Z")));

        service.update(principal, UUID.randomUUID().toString(), request("Libur", new Day("2026-09-28", "Cuti bersama")));

        Map<UUID, OffsetDateTime> expected = new LinkedHashMap<>();
        expected.put(cron, OffsetDateTime.parse("2026-09-29T07:00Z"));
        expected.put(intervalOnHoliday, OffsetDateTime.parse("2026-09-29T00:00Z"));
        assertEquals(expected, triggers.updated, "pemicu selang di hari kerja tidak digeser");
    }

    @Test
    @DisplayName("tanpa perubahan jadwal: tidak ada yang ditulis")
    void unchangedScheduleNotWritten() {
        triggers.onCalendar = List.of(
                new CalendarTrigger(UUID.randomUUID(), "0 7 * * *", 0, "UTC", OffsetDateTime.parse("2026-09-28T07:00Z")));

        service.update(principal, UUID.randomUUID().toString(), request("Libur", new Day("2026-12-25", null)));

        assertTrue(triggers.updated.isEmpty());
    }

    @Test
    @DisplayName("kalender yang tidak ada: 404")
    void updateUnknownCalendar() {
        calendars.exists = false;

        ApiException error = assertThrows(ApiException.class,
                () -> service.update(principal, UUID.randomUUID().toString(), request("Libur")));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
    }

    private static final class FakeCalendars extends CalendarRepository {

        boolean nameTaken;
        boolean exists = true;
        String insertedName;
        List<CalendarDay> savedDays = new ArrayList<>();
        List<String> triggerNames = List.of();
        int deleted;

        FakeCalendars() {
            super(null);
        }

        @Override
        public boolean nameTaken(UUID tenantId, String name, UUID exceptId) {
            return nameTaken;
        }

        @Override
        public boolean exists(UUID tenantId, UUID id) {
            return exists;
        }

        @Override
        public void insert(UUID id, UUID tenantId, String name, String description) {
            insertedName = name;
        }

        @Override
        public int update(UUID tenantId, UUID id, String name, String description) {
            return 1;
        }

        @Override
        public void replaceDays(UUID calendarId, List<CalendarDay> days) {
            savedDays = days;
        }

        @Override
        public List<String> findTriggerNames(UUID tenantId, UUID calendarId) {
            return triggerNames;
        }

        @Override
        public int delete(UUID tenantId, UUID id) {
            deleted++;
            return 1;
        }
    }

    private static final class FakeTriggers extends TriggerRepository {

        List<CalendarTrigger> onCalendar = List.of();
        final Map<UUID, OffsetDateTime> updated = new LinkedHashMap<>();

        FakeTriggers() {
            super(null);
        }

        @Override
        public List<CalendarTrigger> findEnabledByCalendar(UUID tenantId, UUID calendarId) {
            return onCalendar;
        }

        @Override
        public void updateNextRun(UUID triggerId, OffsetDateTime nextRunAt) {
            updated.put(triggerId, nextRunAt);
        }
    }
}
