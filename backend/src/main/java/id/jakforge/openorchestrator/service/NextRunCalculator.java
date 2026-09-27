package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.Cron;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

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
}
