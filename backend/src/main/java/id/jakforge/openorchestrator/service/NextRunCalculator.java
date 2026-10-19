package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.Cron;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Waktu jalan berikutnya sebuah pemicu: dari CRON kalau ada, kalau tidak dari
 * selangnya.
 *
 * <p>Dipakai bersama oleh penyimpanan, penyalaan, dan penjadwal, supaya
 * ketiganya tidak bisa berbeda pendapat tentang kapan sesuatu jatuh tempo.
 */
@Component
@RequiredArgsConstructor
public class NextRunCalculator {

    /** Selang terpendek; selang nol atau negatif berarti tiap menit. */
    static final int MIN_INTERVAL_MINUTES = 1;

    private final Clock clock;

    /**
     * Mengembalikan null untuk cron yang sah tapi tidak pernah cocok (mis.
     * "0 0 31 2 *"); pemanggilnya yang memutuskan apa artinya.
     *
     * @param zone zona tempat ekspresi cron dinilai
     */
    public OffsetDateTime nextRun(String cron, int intervalMinutes, ZoneId zone) {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);

        if (cron == null || cron.isBlank()) {
            return now.plusMinutes(Math.max(MIN_INTERVAL_MINUTES, intervalMinutes));
        }

        ZonedDateTime next = Cron.next(cron, now.toZonedDateTime(), zone);

        return next == null ? null : next.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    /**
     * Seperti {@link #nextRun(String, int, ZoneId)}, tetapi melompati hari
     * non-kerja (V16): tanggal di {@code nonWorkingDays}, dinilai dalam
     * {@code zone}, tidak pernah menjadi waktu jalan.
     *
     * <p>Cron mengambil kecocokan pertama di hari kerja berikutnya ("0 7 * * *"
     * melompat dari Jumat 07:00 ke Senin 07:00 kalau Senin bukan libur).
     * Selang menit tidak punya jam tetap, jadi sesudah hari libur ia mulai
     * lagi pukul 00:00 hari kerja pertama.
     *
     * @return null kalau cron tidak pernah cocok, atau tidak ada hari kerja
     *         dalam {@value #MAX_SKIPPED_DAYS} hari ke depan
     */
    public OffsetDateTime nextRun(String cron, int intervalMinutes, ZoneId zone, Set<LocalDate> nonWorkingDays) {
        OffsetDateTime candidate = nextRun(cron, intervalMinutes, zone);
        if (nonWorkingDays == null || nonWorkingDays.isEmpty()) return candidate;

        ZoneId effectiveZone = zone == null ? ZoneOffset.UTC : zone;

        for (int skipped = 0; candidate != null && skipped < MAX_SKIPPED_DAYS; skipped++) {
            LocalDate day = candidate.atZoneSameInstant(effectiveZone).toLocalDate();
            if (!nonWorkingDays.contains(day)) return candidate;

            ZonedDateTime nextDayStart = day.plusDays(1).atStartOfDay(effectiveZone);

            if (cron == null || cron.isBlank()) {
                candidate = nextDayStart.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
            } else {
                // Cron.next mencari SESUDAH waktu yang diberikan, per menit:
                // satu detik sebelum tengah malam membuat 00:00 ikut diperiksa.
                ZonedDateTime next = Cron.next(cron, nextDayStart.minusSeconds(1), effectiveZone);
                candidate = next == null ? null : next.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
            }
        }

        return null;
    }

    /** Lebih dari sepuluh tahun hari libur berturut-turut berarti kalendernya keliru, bukan libur. */
    static final int MAX_SKIPPED_DAYS = 3700;

    /** Apakah {@code instant}, dinilai dalam {@code zone}, jatuh pada hari non-kerja. */
    public static boolean isNonWorkingDay(OffsetDateTime instant, ZoneId zone, Set<LocalDate> nonWorkingDays) {
        if (instant == null || nonWorkingDays == null || nonWorkingDays.isEmpty()) return false;

        return nonWorkingDays.contains(instant.atZoneSameInstant(zone == null ? ZoneOffset.UTC : zone).toLocalDate());
    }

    /**
     * Pukul 00:00 hari kerja pertama SESUDAH tanggal {@code instant} (dinilai
     * dalam {@code zone}), atau null kalau tidak ada dalam batasnya. Untuk
     * pemicu selang yang jadwalnya kini jatuh di hari libur: ia melanjutkan
     * sesudah libur itu, tidak dimajukan ke sebelum jadwalnya semula.
     */
    public static OffsetDateTime startOfNextWorkingDay(OffsetDateTime instant, ZoneId zone, Set<LocalDate> nonWorkingDays) {
        ZoneId effectiveZone = zone == null ? ZoneOffset.UTC : zone;
        LocalDate day = instant.atZoneSameInstant(effectiveZone).toLocalDate().plusDays(1);

        for (int i = 0; i < MAX_SKIPPED_DAYS; i++, day = day.plusDays(1)) {
            if (nonWorkingDays == null || !nonWorkingDays.contains(day)) {
                return day.atStartOfDay(effectiveZone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
            }
        }

        return null;
    }

    /** Saat ini (UTC), menurut jam yang sama dengan penghitungan jadwal. */
    public OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
