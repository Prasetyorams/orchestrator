package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.LogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catatan tingkat rincian (TRACE, DEBUG) tidak disimpan dan tidak ditampilkan.
 *
 * <p>Layanan dan repositorinya ASLI; yang palsu hanya basis datanya, yang
 * merekam SQL beserta argumennya. Dengan begitu yang diuji juga kalimat SQL
 * yang benar-benar dikirim, bukan hanya keputusan di layanan.
 */
class LogServiceTest {

    private final UUID penyewa = UUID.randomUUID();
    private final DbPerekam db = new DbPerekam();
    private final LogService layanan = new LogService(new LogRepository(db));

    @Test
    @DisplayName("TRACE dan DEBUG dilewati; INFO, WARN, dan ERROR disimpan")
    void rincianTidakDisimpan() {
        Map<String, Object> hasil = layanan.tulis(penyewa, List.of(
                baris("DEBUG", "Assign"),
                baris("trace", "VisualBasicValue<String>"),
                baris("INFO", "Workflow dimulai."),
                baris("WARN", "Elemen lambat muncul."),
                baris("ERROR", "Elemen tidak ditemukan.")));

        assertEquals(3, hasil.get("written"));
        assertEquals(2, hasil.get("skipped"));
        assertEquals(List.of("INFO", "WARN", "ERROR"), tingkatYangDisimpan());
    }

    @Test
    @DisplayName("ERROR tetap menjadi peringatan, DEBUG tidak")
    void peringatanHanyaDariKesalahan() {
        layanan.tulis(penyewa, List.of(baris("DEBUG", "Click"), baris("ERROR", "Gagal")));

        assertEquals(1, db.sqlYangMemuat("INSERT INTO alerts").size());
    }

    @Test
    @DisplayName("baris tanpa tingkat dianggap INFO dan tetap disimpan")
    void tanpaTingkatTetapDisimpan() {
        Map<String, Object> tanpaTingkat = new HashMap<>();
        tanpaTingkat.put("message", "Pesan tanpa tingkat");

        Map<String, Object> hasil = layanan.tulis(penyewa, List.of(tanpaTingkat));

        assertEquals(1, hasil.get("written"));
        assertEquals(List.of("INFO"), tingkatYangDisimpan());
    }

    @Test
    @DisplayName("pembacaan selalu menyaring TRACE dan DEBUG yang terlanjur tersimpan")
    void pembacaanMenyaringRincian() {
        layanan.cari(penyewa, null, null, "cha", null, null);

        List<Object> args = argsTerakhir();

        assertTrue(sqlTerakhir().contains("level NOT IN (?, ?)"), sqlTerakhir());
        assertFalse(sqlTerakhir().contains("level IN"), sqlTerakhir());
        assertTrue(args.contains("TRACE") && args.contains("DEBUG"), args.toString());
    }

    @Test
    @DisplayName("meminta tingkat DEBUG saja dijawab kosong tanpa bertanya ke basis data")
    void memintaRincianKosong() {
        assertEquals(List.of(), layanan.cari(penyewa, List.of("debug"), null, null, null, null));
        assertEquals(List.of(), layanan.cari(penyewa, List.of("TRACE,debug"), null, null, null, null));

        assertTrue(db.sql.isEmpty(), db.sql.toString());
    }

    @Test
    @DisplayName("meminta tingkat ERROR tetap menyaring rincian dan hanya ERROR")
    void memintaTingkatLain() {
        layanan.cari(penyewa, List.of("error"), null, null, null, null);

        assertTrue(sqlTerakhir().contains("level NOT IN (?, ?)") && sqlTerakhir().contains("level IN (?)"),
                sqlTerakhir());
        assertEquals(List.of("ERROR"), tingkatDiSaring());
    }

    @Test
    @DisplayName("lebih dari satu tingkat, dipisah koma maupun parameter berulang")
    void banyakTingkat() {
        layanan.cari(penyewa, List.of("info,error"), null, null, null, null);
        assertEquals(List.of("INFO", "ERROR"), tingkatDiSaring());

        layanan.cari(penyewa, List.of("FATAL", "info"), null, null, null, null);
        assertEquals(List.of("INFO", "FATAL"), tingkatDiSaring());
    }

    @Test
    @DisplayName("WARN ikut membawa WARNING, karena keduanya satu tingkat")
    void warnDanWarning() {
        layanan.cari(penyewa, List.of("warn"), null, null, null, null);
        assertEquals(List.of("WARN", "WARNING"), tingkatDiSaring());

        layanan.cari(penyewa, List.of("warning", "WARN"), null, null, null, null);
        assertEquals(List.of("WARN", "WARNING"), tingkatDiSaring());
    }

    @Test
    @DisplayName("rincian yang diminta bersama tingkat lain dibuang, tingkat lainnya tetap")
    void rincianBersamaTingkatLain() {
        layanan.cari(penyewa, List.of("debug,error"), null, null, null, null);

        assertEquals(List.of("ERROR"), tingkatDiSaring());
    }

    @Test
    @DisplayName("tingkat yang tidak dikenal ditolak 400, bukan diam-diam menjadi INFO")
    void tingkatTakDikenal() {
        ApiException e = assertThrows(ApiException.class,
                () -> layanan.cari(penyewa, List.of("info,peringatan"), null, null, null, null));

        assertEquals(HttpStatus.BAD_REQUEST, e.status());
        assertTrue(db.sql.isEmpty(), db.sql.toString());
    }

    // ---------- peringatan ----------

    @Test
    @DisplayName("peringatan tanpa saringan tingkat menampilkan semua tingkat")
    void peringatanSemuaTingkat() {
        layanan.peringatan(penyewa, null, null, null);

        assertFalse(sqlTerakhir().contains("severity IN"), sqlTerakhir());
    }

    @Test
    @DisplayName("peringatan bisa disaring satu atau beberapa tingkat, tanpa peduli huruf besar")
    void peringatanDisaring() {
        layanan.peringatan(penyewa, null, List.of("error,WARNING"), 20);

        List<Object> args = argsTerakhir();

        assertTrue(sqlTerakhir().contains("severity IN (?, ?)"), sqlTerakhir());
        // Nilainya dikirim dengan ejaan yang tersimpan: Warning, Error.
        assertEquals(List.of("Warning", "Error"), args.subList(2, 4));
        assertEquals(20, args.get(args.size() - 1));
    }

    @Test
    @DisplayName("tingkat peringatan yang tidak dikenal ditolak 400")
    void peringatanTakDikenal() {
        ApiException e = assertThrows(ApiException.class,
                () -> layanan.peringatan(penyewa, null, List.of("gawat"), null));

        assertEquals(HttpStatus.BAD_REQUEST, e.status());
    }

    // ---------- alat ----------

    private static Map<String, Object> baris(String tingkat, String pesan) {
        Map<String, Object> b = new HashMap<>();
        b.put("level", tingkat);
        b.put("message", pesan);
        b.put("robotName", "LAPTOP-uji");
        return b;
    }

    private String sqlTerakhir() {
        return db.sql.get(db.sql.size() - 1);
    }

    private List<Object> argsTerakhir() {
        return Arrays.asList(db.args.get(db.args.size() - 1));
    }

    /**
     * Nilai untuk "level IN (...)" pada pencarian terakhir: argumen sesudah
     * penyewa dan kedua tingkat rincian, sebelum batasnya.
     */
    private List<Object> tingkatDiSaring() {
        List<Object> args = argsTerakhir();
        return args.subList(3, args.size() - 1);
    }

    /** Tingkat dari setiap INSERT ke tabel logs, sesuai urutan kirimannya. */
    private List<Object> tingkatYangDisimpan() {
        List<Object> hasil = new ArrayList<>();

        for (int i = 0; i < db.sql.size(); i++) {
            if (db.sql.get(i).contains("INSERT INTO logs")) hasil.add(db.args.get(i)[1]);
        }

        return hasil;
    }

    /** Basis data palsu: merekam setiap perintah, tidak menjalankan apa pun. */
    private static final class DbPerekam extends Db {

        final List<String> sql = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();

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
        public int exec(String perintah, Object... nilai) {
            sql.add(perintah);
            args.add(nilai);
            return 1;
        }

        List<String> sqlYangMemuat(String potongan) {
            return sql.stream().filter(s -> s.contains(potongan)).toList();
        }
    }
}
