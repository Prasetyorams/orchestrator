package id.jakforge.forgehub.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
public enum Periode {
    TODAY,
    WEEK,
    MONTH,
    YEAR;

    /**
     * Hari pertama periode yang memuat {@code hariIni}.
     *
     * <p>Minggu dimulai hari SENIN, mengikuti ISO-8601 dan kalender kerja di
     * Indonesia — bukan hari Minggu seperti bawaan kalender Amerika.
     */
    public LocalDate hariPertama(LocalDate hariIni) {
        return switch (this) {
            case TODAY -> hariIni;
            case WEEK -> hariIni.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> hariIni.withDayOfMonth(1);
            case YEAR -> hariIni.withDayOfYear(1);
        };
    }

    /** Hari pertama periode SESUDAHNYA: batas atas yang tidak ikut dihitung. */
    public LocalDate hariPertamaBerikutnya(LocalDate hariPertama) {
        return switch (this) {
            case TODAY -> hariPertama.plusDays(1);
            case WEEK -> hariPertama.plusWeeks(1);
            case MONTH -> hariPertama.plusMonths(1);
            case YEAR -> hariPertama.plusYears(1);
        };
    }

    /**
     * Lebar satu batang grafik, sebagai satuan {@code date_trunc} PostgreSQL:
     * per jam untuk hari ini, per hari untuk minggu dan bulan, per bulan untuk
     * tahun. Tiga ratus enam puluh lima batang harian tidak terbaca sebagai
     * apa pun.
     */
    public String satuan() {
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
    public LocalDateTime awalBatangTerakhir(LocalDate hariPertama) {
        return switch (this) {
            case TODAY -> hariPertama.atTime(23, 0);
            case WEEK -> hariPertama.plusDays(6).atStartOfDay();
            case MONTH -> hariPertama.withDayOfMonth(hariPertama.lengthOfMonth()).atStartOfDay();
            case YEAR -> hariPertama.withMonth(12).atStartOfDay();
        };
    }

    /** Nama di API: today, week, month, year. */
    public String nama() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Kosong berarti hari ini; yang tidak dikenal berarti null. */
    public static Periode dari(String teks) {
        if (teks == null || teks.isBlank()) return TODAY;

        for (Periode p : values()) {
            if (p.name().equalsIgnoreCase(teks.trim())) return p;
        }

        return null;
    }
}
