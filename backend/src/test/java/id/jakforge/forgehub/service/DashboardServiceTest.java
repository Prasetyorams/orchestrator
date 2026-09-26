package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.repository.DashboardRepository;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.LogRepository;
import id.jakforge.forgehub.repository.QueueRepository;
import id.jakforge.forgehub.repository.RobotRepository;
import id.jakforge.forgehub.repository.TriggerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batas periode di dasbor: hari, minggu, bulan, dan tahun INI, di zona tampilan.
 *
 * <p>Jamnya dihentikan pada Kamis 24 September 2026 pukul 01.30 WIB — saat
 * yang sengaja dipilih: di UTC hari itu masih Rabu sore, jadi batas yang
 * dihitung di zona yang salah langsung meleset satu hari.
 */
class DashboardServiceTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final UUID penyewa = UUID.randomUUID();
    private final DbPerekam db = new DbPerekam();

    private DashboardService pada(String waktuSetempat) {
        Instant saat = LocalDateTime.parse(waktuSetempat).atZone(WIB).toInstant();

        return new DashboardService(new DashboardRepository(db), new RobotRepository(db),
                new QueueRepository(db), new TriggerRepository(db), new LogRepository(db),
                Clock.fixed(saat, WIB));
    }

    @Test
    @DisplayName("keempat periode dihitung dalam satu kueri, dengan batas tengah malam WIB sebagai UTC")
    void semuaPeriodeSekaligus() {
        Map<String, Object> hasil = pada("2026-09-24T01:30").dasbor(penyewa, null);

        // Kamis 24 September, Senin 21 September, 1 September, dan 1 Januari —
        // masing-masing tengah malam WIB, yaitu pukul 17.00 UTC sehari sebelumnya.
        assertEquals(List.of(OffsetDateTime.parse("2026-09-23T17:00Z"), OffsetDateTime.parse("2026-09-20T17:00Z"),
                        OffsetDateTime.parse("2026-08-31T17:00Z"), OffsetDateTime.parse("2025-12-31T17:00Z"),
                        penyewa),
                argsDari("WITH awal AS"));

        assertEquals("2026-09-24", angkaPeriode(hasil, "today").get("start"));
        assertEquals("2026-09-21", angkaPeriode(hasil, "week").get("start"));
        assertEquals("2026-09-01", angkaPeriode(hasil, "month").get("start"));
        assertEquals("2026-01-01", angkaPeriode(hasil, "year").get("start"));
    }

    @Test
    @DisplayName("minggu yang dibuka hari Minggu masih milik Senin sebelumnya; hari Senin milik dirinya")
    void awalMinggu() {
        assertEquals("2026-09-21", angkaPeriode(pada("2026-09-27T23:59").dasbor(penyewa, null), "week").get("start"));
        assertEquals("2026-09-28", angkaPeriode(pada("2026-09-28T00:00").dasbor(penyewa, null), "week").get("start"));
    }

    @Test
    @DisplayName("awal Januari: minggu ini boleh dimulai di tahun lalu, bulan dan tahun ini tidak")
    void mingguMelintasiTahun() {
        // Jumat 1 Januari 2027; minggunya dimulai Senin 28 Desember 2026.
        Map<String, Object> hasil = pada("2027-01-01T10:00").dasbor(penyewa, null);

        assertEquals("2026-12-28", angkaPeriode(hasil, "week").get("start"));
        assertEquals("2027-01-01", angkaPeriode(hasil, "month").get("start"));
        assertEquals("2027-01-01", angkaPeriode(hasil, "year").get("start"));
    }

    @Test
    @DisplayName("tingkat keberhasilan tiap periode hanya dari pekerjaan yang selesai; belum ada yang selesai berarti 100")
    void tingkatPerPeriode() {
        db.hitungan.putAll(Map.of(
                "weekSuccessful", 9L, "weekFaulted", 1L, "weekTotal", 13L,
                "yearSuccessful", 2L, "yearFaulted", 1L, "yearTotal", 3L));

        Map<String, Object> hasil = pada("2026-09-24T01:30").dasbor(penyewa, null);

        assertEquals(100.0, angkaPeriode(hasil, "today").get("successRate"));
        assertEquals(90.0, angkaPeriode(hasil, "week").get("successRate"));
        assertEquals(9L, angkaPeriode(hasil, "week").get("successful"));
        assertEquals(1L, angkaPeriode(hasil, "week").get("faulted"));
        assertEquals(13L, angkaPeriode(hasil, "week").get("total"));
        assertEquals(66.7, angkaPeriode(hasil, "year").get("successRate"));
    }

    @Test
    @DisplayName("bentuk lama tetap ada: jobs.*Today dan successRate adalah angka hari ini")
    void bentukLama() {
        db.hitungan.putAll(Map.of(
                "running", 2L, "pending", 1L,
                "todaySuccessful", 3L, "todayFaulted", 1L, "todayTotal", 6L,
                "weekSuccessful", 30L, "weekFaulted", 0L));

        Map<String, Object> hasil = pada("2026-09-24T01:30").dasbor(penyewa, null);
        Map<?, ?> pekerjaan = (Map<?, ?>) hasil.get("jobs");

        assertEquals(2L, pekerjaan.get("running"));
        assertEquals(1L, pekerjaan.get("pending"));
        assertEquals(3L, pekerjaan.get("successfulToday"));
        assertEquals(1L, pekerjaan.get("faultedToday"));
        assertEquals(6L, pekerjaan.get("totalToday"));
        assertEquals(75.0, hasil.get("successRate"));
    }

    @Test
    @DisplayName("dasbor sebuah folder menyaring hitungan pekerjaannya ke folder itu")
    void folderIkutDisaring() {
        UUID folder = UUID.randomUUID();

        pada("2026-09-24T01:30").dasbor(penyewa, folder);

        String sql = sqlDari("WITH awal AS");
        List<Object> args = argsDari("WITH awal AS");

        assertTrue(sql.contains("j.folder_id = ?"), sql);
        assertEquals(folder, args.get(args.size() - 1));

        // Pustaka menerima foldernya lewat CTE, sekali saja.
        assertEquals(List.of(penyewa, folder), argsDari("WITH t AS"));
    }

    @Test
    @DisplayName("tanpa folder, hitungannya milik seluruh penyewa")
    void tanpaFolder() {
        pada("2026-09-24T01:30").dasbor(penyewa, null);

        assertTrue(!sqlDari("WITH awal AS").contains("folder_id"), sqlDari("WITH awal AS"));
    }

    @Test
    @DisplayName("yang dihentikan ikut dihitung per periode")
    void dihentikanPerPeriode() {
        db.hitungan.putAll(Map.of("stopping", 1L, "monthStopped", 4L));

        Map<String, Object> hasil = pada("2026-09-24T01:30").dasbor(penyewa, null);

        assertEquals(4L, angkaPeriode(hasil, "month").get("stopped"));
        assertEquals(0L, angkaPeriode(hasil, "today").get("stopped"));
        assertEquals(1L, ((Map<?, ?>) hasil.get("jobs")).get("stopping"));
    }

    @Test
    @DisplayName("irisan donat per proses: lima terbanyak tahun ini, sisanya satu irisan lainnya")
    void irisanProses() {
        List<Map<String, Object>> baris = new ArrayList<>();
        for (String[] p : new String[][] {
                { "A", "9", "1" }, { "B", "7", "0" }, { "C", "5", "4" }, { "D", "4", "0" }, { "E", "3", "0" },
                { "F", "2", "2" }, { "G", "1", "1" }, { "H", "0", "0" } }) {
            baris.add(new HashMap<>(Map.of("processName", p[0],
                    "year", Long.parseLong(p[1]), "today", Long.parseLong(p[2]))));
        }

        List<String> terpilih = DashboardService.prosesTerpilih(baris);
        assertEquals(List.of("A", "B", "C", "D", "E"), terpilih);

        List<Map<String, Object>> irisan = DashboardService.irisanProses(baris, "today", terpilih);

        // Urutannya urutan tahun, bukan urutan hari ini — C tetap di tempat
        // ketiga walau hari ini paling banyak — dan yang nol tetap ikut.
        assertEquals(List.of("A", "B", "C", "D", "E"),
                irisan.subList(0, 5).stream().map(m -> m.get("name")).toList());
        assertEquals(List.of(1L, 0L, 4L, 0L, 0L),
                irisan.subList(0, 5).stream().map(m -> m.get("count")).toList());

        Map<String, Object> lainnya = irisan.get(5);
        assertEquals(true, lainnya.get("other"));
        assertEquals(3L, lainnya.get("count"));
        assertEquals(2, lainnya.get("processes"));
    }

    @Test
    @DisplayName("tanpa proses di luar yang terpilih, tidak ada irisan lainnya")
    void tanpaLainnya() {
        List<Map<String, Object>> baris = new ArrayList<>();
        for (int i = 0; i < 3; i++) baris.add(new HashMap<>(Map.of("processName", "P" + i, "year", 3L - i, "week", 1L)));

        List<Map<String, Object>> irisan =
                DashboardService.irisanProses(baris, "week", DashboardService.prosesTerpilih(baris));

        assertEquals(3, irisan.size());
        assertTrue(irisan.stream().noneMatch(m -> Boolean.TRUE.equals(m.get("other"))));
    }

    @Test
    @DisplayName("grafik hari ini: 24 batang per jam")
    void grafikHariIni() {
        pada("2026-09-24T01:30").riwayat(penyewa, "today");

        assertEquals(List.of(LocalDateTime.parse("2026-09-24T00:00"), LocalDateTime.parse("2026-09-24T23:00"),
                        "hour"),
                argsDari("generate_series(?::timestamp").subList(0, 3));
    }

    @Test
    @DisplayName("grafik bulan ini: satu batang per hari sampai akhir bulan, disaring dengan batas UTC")
    void grafikBulanIni() {
        pada("2026-09-24T01:30").riwayat(penyewa, "month");

        List<Object> args = argsDari("generate_series(?::timestamp");

        assertEquals(LocalDateTime.parse("2026-09-01T00:00"), args.get(0));
        assertEquals(LocalDateTime.parse("2026-09-30T00:00"), args.get(1));
        assertEquals("day", args.get(2));
        assertEquals(OffsetDateTime.parse("2026-08-31T17:00Z"), args.get(4));
        assertEquals(OffsetDateTime.parse("2026-09-30T17:00Z"), args.get(5));
        assertEquals("Asia/Jakarta", args.get(7));
    }

    @Test
    @DisplayName("grafik tahun ini: 12 batang per bulan")
    void grafikTahunIni() {
        pada("2026-09-24T01:30").riwayat(penyewa, "year");

        List<Object> args = argsDari("generate_series(?::timestamp");

        assertEquals(LocalDateTime.parse("2026-01-01T00:00"), args.get(0));
        assertEquals(LocalDateTime.parse("2026-12-01T00:00"), args.get(1));
        assertEquals("month", args.get(2));
        assertEquals(OffsetDateTime.parse("2026-12-31T17:00Z"), args.get(5));
    }

    @Test
    @DisplayName("tanpa periode, riwayat tetap empat belas hari seperti sebelumnya")
    void riwayatLama() {
        pada("2026-09-24T01:30").riwayat(penyewa, null);

        assertTrue(db.sql.get(0).contains("(now() AT TIME ZONE ?)::date - (? - 1)"), db.sql.get(0));
        assertEquals(14, Arrays.asList(db.args.get(0)).get(1));
    }

    @Test
    @DisplayName("periode grafik yang tidak dikenal ditolak 400")
    void periodeTakDikenal() {
        DashboardService layanan = pada("2026-09-24T01:30");

        ApiException e = assertThrows(ApiException.class, () -> layanan.riwayat(penyewa, "decade"));

        assertEquals(HttpStatus.BAD_REQUEST, e.status());
    }

    // ---------- alat ----------

    private static Map<?, ?> angkaPeriode(Map<String, Object> dasbor, String nama) {
        return (Map<?, ?>) ((Map<?, ?>) dasbor.get("periods")).get(nama);
    }

    private String sqlDari(String potonganSql) {
        for (String s : db.sql) {
            if (s.contains(potonganSql)) return s;
        }

        throw new AssertionError("Tidak ada SQL yang memuat: " + potonganSql + "\n" + db.sql);
    }

    private List<Object> argsDari(String potonganSql) {
        for (int i = 0; i < db.sql.size(); i++) {
            if (db.sql.get(i).contains(potonganSql)) return Arrays.asList(db.args.get(i));
        }

        throw new AssertionError("Tidak ada SQL yang memuat: " + potonganSql + "\n" + db.sql);
    }

    /**
     * Basis data palsu: merekam setiap kueri dan menjawab kosong — kecuali
     * kueri hitungan pekerjaan, yang dijawab dengan isi {@link #hitungan}.
     * Kuncinya sudah camelCase, seperti yang dikembalikan Db yang asli.
     */
    private static final class DbPerekam extends Db {

        final List<String> sql = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();
        final Map<String, Object> hitungan = new HashMap<>();

        DbPerekam() {
            super(null);
        }

        @Override
        public List<Map<String, Object>> rows(String perintah, Object... nilai) {
            sql.add(perintah);
            args.add(nilai);
            return new ArrayList<>();
        }

        @Override
        public Map<String, Object> row(String perintah, Object... nilai) {
            sql.add(perintah);
            args.add(nilai);
            return perintah.contains("WITH awal AS") ? new HashMap<>(hitungan) : new HashMap<>();
        }

        @Override
        public Object scalar(String perintah, Object... nilai) {
            sql.add(perintah);
            args.add(nilai);
            return 0L;
        }
    }
}
