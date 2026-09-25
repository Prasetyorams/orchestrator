package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.common.Cron;
import id.jakforge.forgehub.model.Periode;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.DashboardRepository;
import id.jakforge.forgehub.repository.LogRepository;
import id.jakforge.forgehub.repository.QueueRepository;
import id.jakforge.forgehub.repository.RobotRepository;
import id.jakforge.forgehub.repository.TriggerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Isi halaman utama dan pencarian menyeluruh.
 *
 * <p>Seluruh isi dasbor diambil dalam SATU permintaan. Dasbor menyegarkan
 * dirinya tiap beberapa detik; sepuluh permintaan terpisah berarti sepuluh kali
 * perjalanan ke basis data tiap penyegaran, dan angka-angkanya bisa berasal
 * dari sepuluh saat yang berbeda — kartu "berjalan" tidak cocok dengan tabel di
 * bawahnya.
 */
@Service
public class DashboardService {

    private static final int HARI_RIWAYAT = 14;
    private static final int BATAS_PANEL = 25;
    private static final int BATAS_PEMICU = 10;
    private static final int BATAS_PERINGATAN = 8;
    private static final int BATAS_ANTREAN = 8;
    private static final int BATAS_CARI = 30;

    private final DashboardRepository ringkasan;
    private final RobotRepository robots;
    private final QueueRepository antrean;
    private final TriggerRepository pemicu;
    private final LogRepository catatan;
    private final ZoneId zonaTampilan;
    private final Clock jam;

    /**
     * @param zonaTampilan zona untuk menghitung batas "hari ini".
     *
     * <p>Bukan zona server. Di dalam container zona server adalah UTC, dan orang
     * yang membuka dasbor pukul 7 pagi WIB akan melihat angka "hari ini" yang
     * masih menghitung kemarin sore.
     */
    @Autowired
    public DashboardService(DashboardRepository ringkasan, RobotRepository robots,
                            QueueRepository antrean, TriggerRepository pemicu,
                            LogRepository catatan,
                            @Value("${forgehub.display-timezone:UTC}") String zonaTampilan) {
        this(ringkasan, robots, antrean, pemicu, catatan, Clock.system(Cron.zona(zonaTampilan)));
    }

    /**
     * Untuk uji: jam yang bisa dihentikan di saat tertentu. Zona tampilannya
     * adalah zona jam itu.
     */
    DashboardService(DashboardRepository ringkasan, RobotRepository robots,
                     QueueRepository antrean, TriggerRepository pemicu,
                     LogRepository catatan, Clock jam) {
        this.ringkasan = ringkasan;
        this.robots = robots;
        this.antrean = antrean;
        this.pemicu = pemicu;
        this.catatan = catatan;
        this.zonaTampilan = jam.getZone();
        this.jam = jam;
    }

    /**
     * Isi dasbor.
     *
     * <p>Angka berhasil, gagal, total, dan tingkat keberhasilan dikirim untuk
     * KEEMPAT periode sekaligus, di {@code periods}: setiap kartu di dasbor
     * memilih rentangnya sendiri, dan berganti pilihan tidak perlu menunggu
     * permintaan baru. Kartu yang menggambarkan keadaan SAAT INI (robot aktif,
     * pekerjaan berjalan) tidak punya rentang.
     */
    public Map<String, Object> dasbor(UUID tenantId) {
        LocalDate hariIni = LocalDate.now(jam);

        Map<Periode, LocalDate> awal = new EnumMap<>(Periode.class);
        for (Periode p : Periode.values()) awal.put(p, p.hariPertama(hariIni));

        Map<String, Object> hitungan = ringkasan.hitunganPekerjaan(tenantId,
                awalHariUtc(awal.get(Periode.TODAY)), awalHariUtc(awal.get(Periode.WEEK)),
                awalHariUtc(awal.get(Periode.MONTH)), awalHariUtc(awal.get(Periode.YEAR)));

        Map<String, Map<String, Object>> perPeriode = new LinkedHashMap<>();
        for (Periode p : Periode.values()) perPeriode.put(p.nama(), angkaPeriode(hitungan, p.nama(), awal.get(p)));

        // "jobs" dan "successRate" tetap berbentuk seperti sebelum ada periode,
        // untuk klien yang sudah membacanya: angka ...Today selalu hari ini.
        Map<String, Object> angkaHariIni = perPeriode.get(Periode.TODAY.nama());

        Map<String, Object> pekerjaan = new LinkedHashMap<>();
        pekerjaan.put("running", hitungan.get("running"));
        pekerjaan.put("pending", hitungan.get("pending"));
        pekerjaan.put("successfulToday", angkaHariIni.get("successful"));
        pekerjaan.put("faultedToday", angkaHariIni.get("faulted"));
        pekerjaan.put("totalToday", angkaHariIni.get("total"));

        Map<String, Object> antreanHitung = antrean.hitunganButir(tenantId);
        antreanHitung.put("total", antrean.jumlahAntrean(tenantId));

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("robots", robots.hitungan(tenantId));
        hasil.put("jobs", pekerjaan);
        hasil.put("periods", perPeriode);
        hasil.put("queues", antreanHitung);
        hasil.put("library", ringkasan.hitunganPustaka(tenantId));
        hasil.put("successRate", angkaHariIni.get("successRate"));
        hasil.put("unreadAlerts", catatan.belumDibaca(tenantId));
        hasil.put("jobsInProgress", ringkasan.sedangBerjalan(tenantId, BATAS_PANEL));
        hasil.put("activeRobots", robots.untukDasbor(tenantId, BATAS_PANEL));
        hasil.put("upcomingTriggers", pemicu.berikutnya(tenantId, BATAS_PEMICU));
        hasil.put("recentAlerts", catatan.peringatan(tenantId, false, BATAS_PERINGATAN));
        hasil.put("queueSummary", antrean.ringkasan(tenantId, BATAS_ANTREAN));
        hasil.put("serverTime", Db.nowText());

        return hasil;
    }

    /**
     * Batang grafik untuk satu periode: per jam hari ini, per hari minggu dan
     * bulan ini, per bulan tahun ini.
     *
     * <p>Tanpa periode, bentuk lamanya — empat belas hari terakhir — tetap
     * dilayani untuk klien yang belum mengenal periode.
     */
    public List<Map<String, Object>> riwayat(UUID tenantId, String periode) {
        if (periode == null || periode.isBlank()) {
            return ringkasan.riwayat(tenantId, zonaTampilan.getId(), HARI_RIWAYAT);
        }

        Periode p = periode(periode);
        LocalDate hariPertama = p.hariPertama(LocalDate.now(jam));

        return ringkasan.riwayatPeriode(tenantId, zonaTampilan.getId(), p.satuan(),
                hariPertama.atStartOfDay(), p.awalBatangTerakhir(hariPertama),
                awalHariUtc(hariPertama), awalHariUtc(p.hariPertamaBerikutnya(hariPertama)));
    }

    private static Periode periode(String teks) {
        Periode p = Periode.dari(teks);

        if (p == null) {
            throw ApiException.salah(
                    "Periode tidak dikenal: '" + teks.trim() + "'. Pilih today, week, month, atau year.");
        }

        return p;
    }

    /** Tengah malam hari itu di zona tampilan, sebagai waktu UTC untuk dibandingkan dengan kolom. */
    private OffsetDateTime awalHariUtc(LocalDate hari) {
        return hari.atStartOfDay(zonaTampilan).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    /**
     * Angka satu periode dari baris hitungan: hari pertamanya, jumlah yang
     * berhasil, gagal, dan dibuat, serta tingkat keberhasilannya.
     *
     * @param nama nama periode di API, yang juga awalan kolomnya (todaySuccessful, ...)
     */
    private static Map<String, Object> angkaPeriode(Map<String, Object> hitungan, String nama, LocalDate awal) {
        long berhasil = angka(hitungan.get(nama + "Successful"));
        long gagal = angka(hitungan.get(nama + "Faulted"));
        long selesai = berhasil + gagal;

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("start", awal.toString());
        hasil.put("successful", berhasil);
        hasil.put("faulted", gagal);
        hasil.put("total", angka(hitungan.get(nama + "Total")));

        // Tingkat keberhasilan dihitung dari pekerjaan yang sudah SELESAI saja.
        // Memasukkan yang masih berjalan ke penyebut membuat angkanya turun
        // tiap kali pekerjaan baru dimulai, seolah ada yang baru saja gagal.
        hasil.put("successRate", selesai == 0 ? 100.0 : Math.round(berhasil * 1000.0 / selesai) / 10.0);

        return hasil;
    }

    /**
     * Pencarian menyeluruh.
     *
     * <p>Satu huruf tidak dilayani: hasilnya akan berisi hampir semua yang ada
     * dan tidak menolong siapa pun, sementara biayanya enam pemindaian tabel.
     */
    public List<Map<String, Object>> cari(UUID tenantId, String q) {
        if (q == null || q.trim().length() < 2) return Db.kosong();

        return ringkasan.cari(tenantId, pola(q), BATAS_CARI);
    }

    /**
     * Kata kunci menjadi pola LIKE.
     *
     * <p>Tanda % dan _ diloloskan supaya pencarian "100%" tidak berubah menjadi
     * "cocokkan apa saja". Backslash diloloskan lebih dulu, kalau tidak
     * pelolosan berikutnya akan ikut terloloskan.
     */
    private static String pola(String q) {
        return "%" + q.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    private static long angka(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
