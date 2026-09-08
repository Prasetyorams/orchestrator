package id.jakforge.forgehub.common;

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
    private static final int BATAS_TAHUN = 4;

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
        Set<Integer>[] ruas = parse(expression);
        if (ruas == null) return null;

        ZoneId z = zone == null ? ZoneOffset.UTC : zone;

        // Mulai dari menit BERIKUTNYA: pemicu yang baru saja berjalan pada
        // menit ini tidak boleh langsung berjalan lagi di menit yang sama.
        LocalDateTime t = after.withZoneSameInstant(z).toLocalDateTime()
                .truncatedTo(ChronoUnit.MINUTES)
                .plusMinutes(1);

        // Empat tahun cukup untuk menutup 29 Februari pada ekspresi yang paling
        // jarang sekalipun. Kalau dalam rentang itu tidak ada yang cocok,
        // ekspresinya memang tidak akan pernah cocok.
        LocalDateTime batas = t.plusYears(BATAS_TAHUN);

        while (t.isBefore(batas)) {
            if (!ruas[3].contains(t.getMonthValue())) {
                t = t.withDayOfMonth(1).toLocalDate().atStartOfDay().plusMonths(1);
                continue;
            }

            if (!hariCocok(ruas, t)) {
                t = t.toLocalDate().atStartOfDay().plusDays(1);
                continue;
            }

            if (!ruas[1].contains(t.getHour())) {
                t = t.toLocalDate().atStartOfDay().plusHours(t.getHour() + 1L);
                continue;
            }

            if (!ruas[0].contains(t.getMinute())) {
                t = t.plusMinutes(1);
                continue;
            }

            // Waktu lokal yang TIDAK ADA karena jam dimajukan (DST) digeser
            // maju sendiri oleh ZonedDateTime.of, dan itu perilaku yang benar:
            // pemicu pukul 02:30 pada hari jam dimajukan tetap harus berjalan,
            // bukan dilewati diam-diam sampai tahun depan.
            return ZonedDateTime.of(t, z);
        }

        return null;
    }

    /** Tanggal dan hari: kalau KEDUANYA dibatasi, cukup salah satu cocok. */
    private static boolean hariCocok(Set<Integer>[] ruas, LocalDateTime t) {
        boolean tanggalDibatasi = ruas[2].size() < 31;
        boolean hariDibatasi = ruas[4].size() < 7;

        boolean tanggalCocok = ruas[2].contains(t.getDayOfMonth());
        boolean hariCocok = ruas[4].contains(nomorHari(t.getDayOfWeek()));

        if (tanggalDibatasi && hariDibatasi) return tanggalCocok || hariCocok;
        if (tanggalDibatasi) return tanggalCocok;
        if (hariDibatasi) return hariCocok;

        return true;
    }

    /** Java memakai Senin=1..Minggu=7; cron memakai Minggu=0..Sabtu=6. */
    private static int nomorHari(DayOfWeek hari) {
        return hari.getValue() % 7;
    }

    /** Lima himpunan nilai yang diizinkan, atau null kalau tidak sah. */
    @SuppressWarnings("unchecked")
    private static Set<Integer>[] parse(String expression) {
        if (expression == null || expression.isBlank()) return null;

        String[] ruas = expression.trim().split("\\s+");
        if (ruas.length != 5) return null;

        int[][] batas = { {0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 7} };
        Set<Integer>[] hasil = new Set[5];

        for (int i = 0; i < 5; i++) {
            Set<Integer> himpunan = uraiRuas(ruas[i], batas[i][0], batas[i][1]);
            if (himpunan == null) return null;

            // Cron menerima 7 sebagai Minggu, sementara di sini Minggu adalah 0.
            // Disamakan di sini supaya pembandingnya tidak perlu tahu.
            if (i == 4 && himpunan.remove(7)) himpunan.add(0);

            if (himpunan.isEmpty()) return null;
            hasil[i] = himpunan;
        }

        return hasil;
    }

    private static Set<Integer> uraiRuas(String ruas, int min, int max) {
        Set<Integer> hasil = new HashSet<>();

        for (String bagian : ruas.split(",", -1)) {
            if (bagian.isEmpty()) return null;

            int langkah = 1;
            String isi = bagian;

            int garis = bagian.indexOf('/');
            if (garis >= 0) {
                isi = bagian.substring(0, garis);

                Integer n = angka(bagian.substring(garis + 1));
                if (n == null || n < 1) return null;
                langkah = n;
            }

            int mulai;
            int akhir;

            if ("*".equals(isi)) {
                mulai = min;
                akhir = max;
            } else {
                int strip = isi.indexOf('-');

                if (strip > 0) {
                    Integer a = angka(isi.substring(0, strip));
                    Integer b = angka(isi.substring(strip + 1));
                    if (a == null || b == null) return null;

                    mulai = a;
                    akhir = b;
                } else {
                    Integer a = angka(isi);
                    if (a == null) return null;

                    mulai = a;

                    // Angka tunggal DENGAN langkah berarti "dari sini sampai
                    // batas atas": "5/10" pada menit berarti 5, 15, 25, ...
                    akhir = garis >= 0 ? max : a;
                }
            }

            if (mulai < min || akhir > max || mulai > akhir) return null;

            for (int n = mulai; n <= akhir; n += langkah) hasil.add(n);
        }

        return hasil;
    }

    /**
     * Angka bulat, atau null.
     *
     * <p>Integer.parseInt menerima "+5" dan " 5" pada sebagian bentuk, dan
     * ekspresi cron yang longgar begitu akan diterima di sini tapi ditolak di
     * tempat lain. Jadi hanya digit yang lolos.
     */
    private static Integer angka(String teks) {
        if (teks == null || teks.isEmpty() || teks.length() > 4) return null;

        for (int i = 0; i < teks.length(); i++) {
            if (teks.charAt(i) < '0' || teks.charAt(i) > '9') return null;
        }

        return Integer.parseInt(teks);
    }

    /** Zona waktu yang tercatat pada pemicu; UTC kalau namanya tidak dikenal. */
    public static ZoneId zona(String nama) {
        if (nama == null || nama.isBlank()) return ZoneOffset.UTC;

        try {
            return ZoneId.of(nama.trim());
        } catch (Exception e) {
            // Nama zona yang salah ketik TIDAK menghentikan pemicunya; ia
            // berjalan dalam UTC. Berhenti sama sekali karena satu untai yang
            // keliru adalah hukuman yang tidak sebanding.
            return ZoneOffset.UTC;
        }
    }
}
