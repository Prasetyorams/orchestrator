package id.jakforge.openorchestrator.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Waktu jalan berikutnya, dihitung dari jam aplikasi yang dihentikan. */
class NextRunCalculatorTest {

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    /** Jam aplikasi berjalan di zona tampilan; hasilnya tetap dinyatakan dalam UTC. */
    private final NextRunCalculator calculator =
            new NextRunCalculator(Clock.fixed(Instant.parse("2026-09-27T10:00:30Z"), JAKARTA));

    @Test
    @DisplayName("tanpa cron: sekarang ditambah selangnya, dalam UTC")
    void intervalFromNow() {
        OffsetDateTime next = calculator.nextRun(null, 15, ZoneOffset.UTC);

        assertEquals(OffsetDateTime.parse("2026-09-27T10:15:30Z"), next);
        assertEquals(ZoneOffset.UTC, next.getOffset());
    }

    @Test
    @DisplayName("selang nol atau negatif berarti tiap menit")
    void nonPositiveIntervalMeansEveryMinute() {
        assertEquals(OffsetDateTime.parse("2026-09-27T10:01:30Z"), calculator.nextRun(" ", 0, ZoneOffset.UTC));
        assertEquals(OffsetDateTime.parse("2026-09-27T10:01:30Z"), calculator.nextRun(null, -5, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("cron dinilai di zona pemicunya: 07:00 WIB adalah 00:00 UTC")
    void cronInTriggerZone() {
        assertEquals(OffsetDateTime.parse("2026-09-28T00:00Z"), calculator.nextRun("0 7 * * *", 60, JAKARTA));
    }

    @Test
    @DisplayName("cron yang sah tapi tidak pernah cocok menghasilkan null")
    void impossibleCronGivesNull() {
        assertNull(calculator.nextRun("0 0 31 2 *", 60, ZoneOffset.UTC));
    }

    // --- kalender hari non-kerja (V16); sekarang Minggu 27 Sep 2026, 17:00 WIB ---

    private static Set<LocalDate> days(String... dates) {
        Set<LocalDate> set = new HashSet<>();
        for (String d : dates) set.add(LocalDate.parse(d));
        return set;
    }

    @Test
    @DisplayName("kalender kosong atau null: sama persis dengan tanpa kalender")
    void emptyCalendarChangesNothing() {
        assertEquals(calculator.nextRun("0 7 * * *", 60, JAKARTA), calculator.nextRun("0 7 * * *", 60, JAKARTA, Set.of()));
        assertEquals(calculator.nextRun(null, 15, ZoneOffset.UTC), calculator.nextRun(null, 15, ZoneOffset.UTC, null));
    }

    @Test
    @DisplayName("cron hari kerja melompati libur berturut-turut ke kecocokan pertama sesudahnya")
    void cronSkipsConsecutiveHolidays() {
        // 0 7 * * 1-5 WIB: berikutnya Senin 28; 28–30 libur -> Kamis 1 Oktober 07:00 WIB = 00:00 UTC.
        assertEquals(OffsetDateTime.parse("2026-10-01T00:00Z"),
                calculator.nextRun("0 7 * * 1-5", 60, JAKARTA, days("2026-09-28", "2026-09-29", "2026-09-30")));
    }

    @Test
    @DisplayName("tanggal dinilai di zona pemicu, bukan UTC")
    void datesJudgedInTriggerZone() {
        // 07:00 WIB Senin 28 = 00:00 UTC Senin 28; libur 28 menurut WIB -> Selasa 29.
        assertEquals(OffsetDateTime.parse("2026-09-29T00:00Z"), calculator.nextRun("0 7 * * *", 60, JAKARTA, days("2026-09-28")));
        // Dinilai di UTC: berikutnya 28 07:00 UTC; tanggal 28 libur -> 29 07:00 UTC.
        assertEquals(OffsetDateTime.parse("2026-09-29T07:00Z"), calculator.nextRun("0 7 * * *", 60, ZoneOffset.UTC, days("2026-09-28")));
    }

    @Test
    @DisplayName("cron tengah malam: 00:00 hari kerja sesudah libur tetap terpilih")
    void midnightCronAfterHoliday() {
        assertEquals(OffsetDateTime.parse("2026-09-28T17:00Z"), calculator.nextRun("0 0 * * *", 60, JAKARTA, days("2026-09-28")));
    }

    @Test
    @DisplayName("selang yang jatuh di hari libur mulai lagi 00:00 hari kerja berikutnya di zona pemicu")
    void intervalResumesAtMidnight() {
        // 17:00 WIB + 15 menit masih tanggal 27 (libur) -> 28 00:00 WIB = 27 17:00 UTC.
        assertEquals(OffsetDateTime.parse("2026-09-27T17:00Z"), calculator.nextRun(null, 15, JAKARTA, days("2026-09-27")));
        // Libur 27 dan 28 -> 29 00:00 WIB.
        assertEquals(OffsetDateTime.parse("2026-09-28T17:00Z"), calculator.nextRun(null, 15, JAKARTA, days("2026-09-27", "2026-09-28")));
    }

    @Test
    @DisplayName("semua hari dalam batasnya libur: null, bukan berputar selamanya")
    void endlessHolidaysGiveNull() {
        Set<LocalDate> all = new HashSet<>();
        LocalDate d = LocalDate.parse("2026-09-27");
        for (int i = 0; i <= NextRunCalculator.MAX_SKIPPED_DAYS + 1; i++) all.add(d.plusDays(i));

        assertNull(calculator.nextRun("0 7 * * *", 60, ZoneOffset.UTC, all));
    }

    @Test
    @DisplayName("isNonWorkingDay menilai tanggal di zona yang diberikan")
    void nonWorkingDayCheck() {
        OffsetDateTime t = OffsetDateTime.parse("2026-09-27T18:00Z"); // 28 Sep 01:00 WIB
        assertTrue(NextRunCalculator.isNonWorkingDay(t, JAKARTA, days("2026-09-28")));
        assertFalse(NextRunCalculator.isNonWorkingDay(t, ZoneOffset.UTC, days("2026-09-28")));
        assertFalse(NextRunCalculator.isNonWorkingDay(null, JAKARTA, days("2026-09-28")));
    }
}
