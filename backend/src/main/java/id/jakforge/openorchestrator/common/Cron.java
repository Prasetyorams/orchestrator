package id.jakforge.openorchestrator.common;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

/**
 * Penilai ekspresi cron lima ruas: menit, jam, tanggal, bulan, hari.
 *
 * <pre>
 *     * * * * *
 *     │ │ │ │ └── hari dalam minggu (0-6, 0 = Minggu; 7 juga Minggu)
 *     │ │ │ └──── bulan (1-12)
 *     │ │ └────── tanggal (1-31)
 *     │ └──────── jam (0-23)
 *     └────────── menit (0-59)
 * </pre>
 *
 * <p>Yang didukung: {@code *}, angka, daftar {@code 1,15}, rentang {@code 1-5},
 * dan langkah {@code * / 15} atau {@code 0-30/10}.
 *
 * <p>Ditulis sendiri, bukan menarik pustaka: yang dibutuhkan hanya "kapan
 * berikutnya", ekspresinya lima ruas, dan aturannya sudah baku sejak puluhan
 * tahun. Menambah paket untuk seratus baris yang bisa diuji tuntas bukan
 * pertukaran yang masuk akal.
 *
 * <p><b>Dua aturan yang mudah terlewat.</b>
 *
 * <p>Pertama: kalau TANGGAL dan HARI dua-duanya dibatasi, yang berlaku adalah
 * SALAH SATU cocok, bukan keduanya. Itu perilaku cron asli (Vixie), dan orang
 * yang menulis {@code 0 0 1 * 1} mengharapkan tanggal 1 ATAU setiap Senin,
 * bukan tanggal 1 yang kebetulan Senin.
 *
 * <p>Kedua: ekspresinya dinilai dalam ZONA WAKTU pemicunya, bukan UTC. Versi
 * .NET menyimpan kolom timezone tapi tidak pernah memakainya — akibatnya
 * {@code 0 7 * * *} bertanda Asia/Jakarta menyala pukul 14:00 WIB, dan tidak
 * ada apa pun di layar yang menjelaskan kenapa. Di sini zonanya benar-benar
 * dipakai.
 */
public final class Cron {

    /** Jumlah tahun ke depan yang ditelusuri sebelum menyerah. */
    private static final int MAX_YEARS_AHEAD = 4;

    private static final int FIELD_COUNT = 5;
    private static final int MINUTE = 0;
    private static final int HOUR = 1;
    private static final int DAY_OF_MONTH = 2;
    private static final int MONTH = 3;
    private static final int DAY_OF_WEEK = 4;

    private static final int DAYS_IN_LONGEST_MONTH = 31;
    private static final int DAYS_IN_WEEK = 7;

    /** Cron menerima 7 sebagai Minggu; di sini Minggu selalu 0. */
    private static final int SUNDAY_ALIAS = 7;
    private static final int SUNDAY = 0;

    /** Batas bawah dan atas tiap ruas, dalam urutan ruasnya. */
    private static final int[][] FIELD_RANGES = { {0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 7} };

    /** Angka dalam ekspresi paling banyak empat digit. */
    private static final int MAX_NUMBER_DIGITS = 4;

    private Cron() {
    }

    /** Benar kalau ekspresinya bisa dipakai. */
    public static boolean isValid(String expression) {
        return parse(expression) != null;
    }

    /**
     * Waktu berjalan berikutnya SESUDAH {@code after}, dinilai dalam
     * {@code zone}. Null kalau ekspresinya tidak sah atau tidak pernah cocok.
     */
    public static ZonedDateTime next(String expression, ZonedDateTime after, ZoneId zone) {
        Set<Integer>[] fields = parse(expression);
        if (fields == null) return null;

        ZoneId effectiveZone = zone == null ? ZoneOffset.UTC : zone;

        // Mulai dari menit BERIKUTNYA: pemicu yang baru saja berjalan pada
        // menit ini tidak boleh langsung berjalan lagi di menit yang sama.
        LocalDateTime candidate = after.withZoneSameInstant(effectiveZone).toLocalDateTime()
                .truncatedTo(ChronoUnit.MINUTES)
                .plusMinutes(1);

        // Empat tahun cukup untuk menutup 29 Februari pada ekspresi yang paling
        // jarang sekalipun. Kalau dalam rentang itu tidak ada yang cocok,
        // ekspresinya memang tidak akan pernah cocok.
        LocalDateTime giveUpAt = candidate.plusYears(MAX_YEARS_AHEAD);

        while (candidate.isBefore(giveUpAt)) {
            if (!fields[MONTH].contains(candidate.getMonthValue())) {
                candidate = candidate.withDayOfMonth(1).toLocalDate().atStartOfDay().plusMonths(1);
                continue;
            }

            if (!dayMatches(fields, candidate)) {
                candidate = candidate.toLocalDate().atStartOfDay().plusDays(1);
                continue;
            }

            if (!fields[HOUR].contains(candidate.getHour())) {
                candidate = candidate.toLocalDate().atStartOfDay().plusHours(candidate.getHour() + 1L);
                continue;
            }

            if (!fields[MINUTE].contains(candidate.getMinute())) {
                candidate = candidate.plusMinutes(1);
                continue;
            }

            // Waktu lokal yang TIDAK ADA karena jam dimajukan (DST) digeser
            // maju sendiri oleh ZonedDateTime.of, dan itu perilaku yang benar:
            // pemicu pukul 02:30 pada hari jam dimajukan tetap harus berjalan,
            // bukan dilewati diam-diam sampai tahun depan.
            return ZonedDateTime.of(candidate, effectiveZone);
        }

        return null;
    }

    /** Zona waktu yang tercatat pada pemicu; UTC kalau namanya tidak dikenal. */
    public static ZoneId zoneOrUtc(String zoneName) {
        if (zoneName == null || zoneName.isBlank()) return ZoneOffset.UTC;

        try {
            return ZoneId.of(zoneName.trim());
        } catch (Exception e) {
            // Nama zona yang salah ketik TIDAK menghentikan pemicunya; ia
            // berjalan dalam UTC. Berhenti sama sekali karena satu untai yang
            // keliru adalah hukuman yang tidak sebanding.
            return ZoneOffset.UTC;
        }
    }

    /** Tanggal dan hari: kalau KEDUANYA dibatasi, cukup salah satu cocok. */
    private static boolean dayMatches(Set<Integer>[] fields, LocalDateTime candidate) {
        boolean dayOfMonthRestricted = fields[DAY_OF_MONTH].size() < DAYS_IN_LONGEST_MONTH;
        boolean dayOfWeekRestricted = fields[DAY_OF_WEEK].size() < DAYS_IN_WEEK;

        boolean dayOfMonthMatches = fields[DAY_OF_MONTH].contains(candidate.getDayOfMonth());
        boolean dayOfWeekMatches = fields[DAY_OF_WEEK].contains(cronDayOfWeek(candidate.getDayOfWeek()));

        if (dayOfMonthRestricted && dayOfWeekRestricted) return dayOfMonthMatches || dayOfWeekMatches;
        if (dayOfMonthRestricted) return dayOfMonthMatches;
        if (dayOfWeekRestricted) return dayOfWeekMatches;

        return true;
    }

    /** Java memakai Senin=1..Minggu=7; cron memakai Minggu=0..Sabtu=6. */
    private static int cronDayOfWeek(DayOfWeek day) {
        return day.getValue() % DAYS_IN_WEEK;
    }

    /** Lima himpunan nilai yang diizinkan, atau null kalau tidak sah. */
    @SuppressWarnings("unchecked")
    private static Set<Integer>[] parse(String expression) {
        if (expression == null || expression.isBlank()) return null;

        String[] parts = expression.trim().split("\\s+");
        if (parts.length != FIELD_COUNT) return null;

        Set<Integer>[] fields = new Set[FIELD_COUNT];

        for (int i = 0; i < FIELD_COUNT; i++) {
            Set<Integer> allowed = parseField(parts[i], FIELD_RANGES[i][0], FIELD_RANGES[i][1]);
            if (allowed == null) return null;

            // Cron menerima 7 sebagai Minggu, sementara di sini Minggu adalah 0.
            // Disamakan di sini supaya pembandingnya tidak perlu tahu.
            if (i == DAY_OF_WEEK && allowed.remove(SUNDAY_ALIAS)) allowed.add(SUNDAY);

            if (allowed.isEmpty()) return null;
            fields[i] = allowed;
        }

        return fields;
    }

    private static Set<Integer> parseField(String field, int min, int max) {
        Set<Integer> allowed = new HashSet<>();

        for (String part : field.split(",", -1)) {
            if (part.isEmpty()) return null;

            int step = 1;
            String range = part;

            int slash = part.indexOf('/');
            if (slash >= 0) {
                range = part.substring(0, slash);

                Integer parsedStep = parseNumber(part.substring(slash + 1));
                if (parsedStep == null || parsedStep < 1) return null;
                step = parsedStep;
            }

            int start;
            int end;

            if ("*".equals(range)) {
                start = min;
                end = max;
            } else {
                int dash = range.indexOf('-');

                if (dash > 0) {
                    Integer from = parseNumber(range.substring(0, dash));
                    Integer to = parseNumber(range.substring(dash + 1));
                    if (from == null || to == null) return null;

                    start = from;
                    end = to;
                } else {
                    Integer single = parseNumber(range);
                    if (single == null) return null;

                    start = single;

                    // Angka tunggal DENGAN langkah berarti "dari sini sampai
                    // batas atas": "5/10" pada menit berarti 5, 15, 25, ...
                    end = slash >= 0 ? max : single;
                }
            }

            if (start < min || end > max || start > end) return null;

            for (int value = start; value <= end; value += step) allowed.add(value);
        }

        return allowed;
    }

    /**
     * Angka bulat, atau null.
     *
     * <p>Integer.parseInt menerima "+5" dan " 5" pada sebagian bentuk, dan
     * ekspresi cron yang longgar begitu akan diterima di sini tapi ditolak di
     * tempat lain. Jadi hanya digit yang lolos.
     */
    private static Integer parseNumber(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_NUMBER_DIGITS) return null;

        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') return null;
        }

        return Integer.parseInt(text);
    }
}
