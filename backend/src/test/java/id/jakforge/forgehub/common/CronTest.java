package id.jakforge.forgehub.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cron adalah tempat kesalahan bersembunyi paling lama.
 *
 * <p>Pemicu yang meleset sejam tidak menghasilkan pesan galat apa pun — ia
 * hanya berjalan pada waktu yang salah, dan biasanya baru disadari berminggu
 * kemudian. Karena itu yang diuji di sini bukan "tidak melempar", melainkan
 * waktu tepatnya, termasuk zona waktu dan aturan Vixie.
 */
class CronTest {

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    private static ZonedDateTime utc(String iso) {
        return ZonedDateTime.parse(iso);
    }

    // -----------------------------------------------------------------
    // Bentuk yang sah dan yang tidak
    // -----------------------------------------------------------------

    @Test
    @DisplayName("bentuk yang sah diterima")
    void acceptsValidExpressions() {
        assertTrue(Cron.isValid("* * * * *"));
        assertTrue(Cron.isValid("0 7 * * 1-5"));
        assertTrue(Cron.isValid("*/15 * * * *"));
        assertTrue(Cron.isValid("0,30 8-17 * * *"));
        assertTrue(Cron.isValid("5/10 * * * *"));
        assertTrue(Cron.isValid("0 0 1 1 0"));
        assertTrue(Cron.isValid("0 0 * * 7"), "7 juga berarti Minggu");
        assertTrue(Cron.isValid("  0   7  *  *  *  "), "spasi berlebih dimaafkan");
    }

    @Test
    @DisplayName("bentuk yang cacat ditolak, bukan diterima diam-diam")
    void rejectsMalformedExpressions() {
        assertFalse(Cron.isValid(null));
        assertFalse(Cron.isValid(""));
        assertFalse(Cron.isValid("   "));
        assertFalse(Cron.isValid("* * * *"), "empat ruas");
        assertFalse(Cron.isValid("* * * * * *"), "enam ruas");
        assertFalse(Cron.isValid("60 * * * *"), "menit 60");
        assertFalse(Cron.isValid("* 24 * * *"), "jam 24");
        assertFalse(Cron.isValid("* * 0 * *"), "tanggal 0");
        assertFalse(Cron.isValid("* * 32 * *"), "tanggal 32");
        assertFalse(Cron.isValid("* * * 13 *"), "bulan 13");
        assertFalse(Cron.isValid("* * * * 8"), "hari 8");
        assertFalse(Cron.isValid("10-5 * * * *"), "rentang terbalik");
        assertFalse(Cron.isValid("*/0 * * * *"), "langkah nol");
        assertFalse(Cron.isValid("abc * * * *"));
        assertFalse(Cron.isValid("1,,2 * * * *"), "bagian kosong");
        assertFalse(Cron.isValid("-5 * * * *"));
    }

    // -----------------------------------------------------------------
    // Waktu berikutnya
    // -----------------------------------------------------------------

    @Test
    @DisplayName("tiap menit: menit berikutnya, bukan menit ini")
    void everyMinuteMovesForward() {
        ZonedDateTime after = utc("2026-09-08T10:30:45Z");
        ZonedDateTime next = Cron.next("* * * * *", after, ZoneOffset.UTC);

        // Detiknya dibuang, lalu maju satu menit. Kalau menit ini ikut
        // dianggap cocok, pemicu "tiap menit" akan berjalan dua kali.
        assertEquals(utc("2026-09-08T10:31:00Z"), next);
    }

    @Test
    @DisplayName("tiap 15 menit menempel di kelipatannya")
    void everyFifteenSnapsToQuarter() {
        assertEquals(utc("2026-09-08T10:45:00Z"),
                Cron.next("*/15 * * * *", utc("2026-09-08T10:31:00Z"), ZoneOffset.UTC));

        assertEquals(utc("2026-09-08T11:00:00Z"),
                Cron.next("*/15 * * * *", utc("2026-09-08T10:45:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("angka tunggal dengan langkah berarti sampai batas atas")
    void singleValueWithStep() {
        assertEquals(utc("2026-09-08T10:35:00Z"),
                Cron.next("5/10 * * * *", utc("2026-09-08T10:31:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("hari kerja pukul 07:00 melewati akhir pekan")
    void weekdaysSkipWeekend() {
        // 2026-09-11 adalah Jumat; berikutnya harus Senin 14, bukan Sabtu 12.
        ZonedDateTime fridayAfternoon = utc("2026-09-11T09:00:00Z");
        ZonedDateTime next = Cron.next("0 7 * * 1-5", fridayAfternoon, ZoneOffset.UTC);

        assertEquals(utc("2026-09-14T07:00:00Z"), next);
        assertEquals("MONDAY", next.getDayOfWeek().name());
    }

    @Test
    @DisplayName("aturan Vixie: tanggal ATAU hari, bukan keduanya")
    void dayOfMonthOrDayOfWeek() {
        // "tanggal 1 ATAU setiap Senin". Dari Kamis 2026-09-03, yang terdekat
        // adalah Senin 2026-09-07 — bukan menunggu tanggal 1 Oktober.
        ZonedDateTime next = Cron.next("0 0 1 * 1", utc("2026-09-03T12:00:00Z"), ZoneOffset.UTC);
        assertEquals(utc("2026-09-07T00:00:00Z"), next);

        // Dan dari Senin 7, berikutnya Senin 14 — kecuali ada tanggal 1 di
        // antaranya, yang tidak ada di September.
        assertEquals(utc("2026-09-14T00:00:00Z"),
                Cron.next("0 0 1 * 1", utc("2026-09-07T00:00:00Z"), ZoneOffset.UTC));

        // Kalau HANYA tanggal yang dibatasi, harinya tidak berpengaruh.
        assertEquals(utc("2026-10-01T00:00:00Z"),
                Cron.next("0 0 1 * *", utc("2026-09-03T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("7 dan 0 sama-sama berarti Minggu")
    void sevenIsSunday() {
        ZonedDateTime tuesday = utc("2026-09-08T12:00:00Z");

        assertEquals(Cron.next("0 0 * * 0", tuesday, ZoneOffset.UTC),
                     Cron.next("0 0 * * 7", tuesday, ZoneOffset.UTC));

        assertEquals("SUNDAY", Cron.next("0 0 * * 7", tuesday, ZoneOffset.UTC).getDayOfWeek().name());
    }

    @Test
    @DisplayName("29 Februari ditemukan di tahun kabisat")
    void findsLeapDay() {
        ZonedDateTime next = Cron.next("0 0 29 2 *", utc("2026-03-01T00:00:00Z"), ZoneOffset.UTC);

        assertNotNull(next, "2028 adalah tahun kabisat dan masih dalam jangkauan 4 tahun.");
        assertEquals(2028, next.getYear());
        assertEquals(2, next.getMonthValue());
        assertEquals(29, next.getDayOfMonth());
    }

    @Test
    @DisplayName("tanggal yang tidak pernah ada mengembalikan null, bukan menggantung")
    void impossibleDateGivesNull() {
        // 31 Februari tidak pernah ada. Yang penting: ia BERHENTI mencari.
        assertNull(Cron.next("0 0 31 2 *", utc("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("ekspresi tidak sah mengembalikan null")
    void invalidGivesNull() {
        assertNull(Cron.next("bukan cron", utc("2026-09-08T10:00:00Z"), ZoneOffset.UTC));
        assertNull(Cron.next(null, utc("2026-09-08T10:00:00Z"), ZoneOffset.UTC));
    }

    // -----------------------------------------------------------------
    // Zona waktu — inti perbaikan atas versi .NET
    // -----------------------------------------------------------------

    @Test
    @DisplayName("07:00 Asia/Jakarta adalah 00:00 UTC, bukan 07:00 UTC")
    void respectsTimezone() {
        ZonedDateTime after = utc("2026-09-08T10:00:00Z");
        ZonedDateTime next = Cron.next("0 7 * * *", after, JAKARTA);

        assertEquals(7, next.getHour(), "jamnya 07:00 menurut Jakarta");
        assertEquals(JAKARTA, next.getZone());

        // Jakarta UTC+7, jadi 07:00 WIB = 00:00 UTC hari yang sama.
        assertEquals(utc("2026-09-09T00:00:00Z").toInstant(), next.toInstant());
    }

    @Test
    @DisplayName("zona yang berbeda menghasilkan saat yang berbeda")
    void differentZonesDiffer() {
        ZonedDateTime after = utc("2026-09-08T10:00:00Z");

        assertNotEqualsInstant(
                Cron.next("0 7 * * *", after, JAKARTA),
                Cron.next("0 7 * * *", after, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("nama zona yang salah ketik jatuh ke UTC, tidak melempar")
    void unknownZoneFallsBackToUtc() {
        assertEquals(ZoneOffset.UTC, Cron.zoneOrUtc("Asia/Djakarta-yang-salah"));
        assertEquals(ZoneOffset.UTC, Cron.zoneOrUtc(null));
        assertEquals(ZoneOffset.UTC, Cron.zoneOrUtc("  "));
        assertEquals(JAKARTA, Cron.zoneOrUtc("Asia/Jakarta"));
        assertEquals(JAKARTA, Cron.zoneOrUtc("  Asia/Jakarta  "));
    }

    @Test
    @DisplayName("hari yang jamnya dimajukan tidak melewatkan pemicunya")
    void survivesDaylightSavingGap() {
        // Eropa/Berlin memajukan jam pada 2026-03-29 pukul 02:00 -> 03:00,
        // jadi 02:30 tidak ada hari itu. Pemicunya harus tetap berjalan,
        // digeser maju — bukan hilang sampai tahun depan.
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        ZonedDateTime next = Cron.next("30 2 * * *", ZonedDateTime.parse("2026-03-28T12:00:00Z"), berlin);

        assertNotNull(next);
        assertEquals(29, next.getDayOfMonth(), "tetap pada hari itu");
        assertEquals(3, next.getHour(), "02:30 yang tidak ada digeser ke 03:30");
    }

    private static void assertNotEqualsInstant(ZonedDateTime first, ZonedDateTime second) {
        assertFalse(first.toInstant().equals(second.toInstant()),
                "Kedua zona menghasilkan saat yang sama: " + first + " vs " + second);
    }
}
