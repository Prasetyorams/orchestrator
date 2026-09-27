package id.jakforge.forgehub.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * Rentang angka di dasbor: hari ini, minggu ini, bulan ini, atau tahun ini.
 *
 * <p>Rentang KALENDER, bukan "tujuh hari terakhir". "Minggu ini" pada Senin
 * pagi memang hampir kosong, dan itulah jawaban untuk pertanyaan yang sama
 * dengan laporan mingguan. Rentang bergulir membuat angka hari Senin ikut
 * menghitung Sabtu lalu, dan dua orang yang membandingkan dasbor dengan
 * laporannya akan mendapat angka yang berbeda.
 *
 * <p>Seluruh batasnya dihitung di zona TAMPILAN, bukan zona server — alasannya
 * sama dengan batas "hari ini" di {@code DashboardService}.
 */
public enum DashboardPeriod {
    TODAY,
    WEEK,
    MONTH,
    YEAR;

    /** Batang terakhir grafik harian: pukul 23.00, batang per jam. */
    private static final LocalTime LAST_HOURLY_BUCKET = LocalTime.of(23, 0);

    /** Batang terakhir grafik mingguan: hari ketujuh, dihitung dari hari pertama. */
    private static final int DAYS_AFTER_FIRST_DAY_OF_WEEK = 6;

    /**
     * Hari pertama periode yang memuat {@code today}.
     *
     * <p>Minggu dimulai hari SENIN, mengikuti ISO-8601 dan kalender kerja di
     * Indonesia — bukan hari Minggu seperti bawaan kalender Amerika.
     */
    public LocalDate firstDay(LocalDate today) {
        return switch (this) {
            case TODAY -> today;
            case WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> today.withDayOfMonth(1);
            case YEAR -> today.withDayOfYear(1);
        };
    }

    /** Hari pertama periode SESUDAHNYA: batas atas yang tidak ikut dihitung. */
    public LocalDate firstDayOfNextPeriod(LocalDate firstDay) {
        return switch (this) {
            case TODAY -> firstDay.plusDays(1);
            case WEEK -> firstDay.plusWeeks(1);
            case MONTH -> firstDay.plusMonths(1);
            case YEAR -> firstDay.plusYears(1);
        };
    }

    /**
     * Lebar satu batang grafik, sebagai satuan {@code date_trunc} PostgreSQL:
     * per jam untuk hari ini, per hari untuk minggu dan bulan, per bulan untuk
     * tahun. Tiga ratus enam puluh lima batang harian tidak terbaca sebagai
     * apa pun.
     */
    public String bucketUnit() {
        return switch (this) {
            case TODAY -> "hour";
            case WEEK, MONTH -> "day";
            case YEAR -> "month";
        };
    }

    /**
     * Awal batang TERAKHIR, bukan akhir periodenya: generate_series ikut
     * menghitung batas atasnya, jadi batas yang dikirim adalah batang yang
     * masih harus muncul.
     */
    public LocalDateTime lastBucketStart(LocalDate firstDay) {
        return switch (this) {
            case TODAY -> firstDay.atTime(LAST_HOURLY_BUCKET);
            case WEEK -> firstDay.plusDays(DAYS_AFTER_FIRST_DAY_OF_WEEK).atStartOfDay();
            case MONTH -> firstDay.withDayOfMonth(firstDay.lengthOfMonth()).atStartOfDay();
            case YEAR -> firstDay.withMonth(Month.DECEMBER.getValue()).atStartOfDay();
        };
    }

    /** Nama di API: today, week, month, year. */
    public String apiName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Kosong berarti hari ini; yang tidak dikenal berarti null. */
    public static DashboardPeriod parse(String text) {
        if (text == null || text.isBlank()) return TODAY;

        for (DashboardPeriod period : values()) {
            if (period.name().equalsIgnoreCase(text.trim())) return period;
        }

        return null;
    }
}
