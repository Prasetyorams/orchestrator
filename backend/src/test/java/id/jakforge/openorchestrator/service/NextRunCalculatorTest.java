package id.jakforge.openorchestrator.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
