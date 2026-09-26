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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Irisan donat per proses. Lebih dari itu, sisanya digabung menjadi satu
     * irisan "lainnya": dua puluh irisan setipis rambut tidak terbaca sebagai
     * apa pun, dan warnanya mulai berulang.
     */
    private static final int IRISAN_PROSES = 5;

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
     * Isi dasbor sebuah folder.
     *
     * <p>Angka pekerjaan dikirim untuk KEEMPAT periode sekaligus, di
     * {@code periods} dan {@code processBreakdown}: setiap donat di dasbor
     * memilih rentangnya sendiri, dan berganti pilihan tidak perlu menunggu
     * permintaan baru. Yang menggambarkan keadaan SAAT INI — robot, pekerjaan
     * yang sedang berjalan — tidak punya rentang.
     *
     * <p>Peringatan tetap milik seluruh penyewa: peringatan tidak tinggal di
     * folder, dan robot yang terputus perlu terlihat dari folder mana pun.
     *
     * @param folderId null berarti seluruh penyewa, seperti sebelum folder ada.
     */
    public Map<String, Object> dasbor(UUID tenantId, UUID folderId) {
        LocalDate hariIni = LocalDate.now(jam);

        Map<Periode, LocalDate> awal = new EnumMap<>(Periode.class);
        for (Periode p : Periode.values()) awal.put(p, p.hariPertama(hariIni));

        OffsetDateTime hari = awalHariUtc(awal.get(Periode.TODAY));
        OffsetDateTime minggu = awalHariUtc(awal.get(Periode.WEEK));
        OffsetDateTime bulan = awalHariUtc(awal.get(Periode.MONTH));
        OffsetDateTime tahun = awalHariUtc(awal.get(Periode.YEAR));

        Map<String, Object> hitungan = ringkasan.hitunganPekerjaan(tenantId, folderId, hari, minggu, bulan, tahun);

        Map<String, Map<String, Object>> perPeriode = new LinkedHashMap<>();
        for (Periode p : Periode.values()) perPeriode.put(p.nama(), angkaPeriode(hitungan, p.nama(), awal.get(p)));

        List<Map<String, Object>> barisProses = ringkasan.perProses(tenantId, folderId, hari, minggu, bulan, tahun);
        List<String> terpilih = prosesTerpilih(barisProses);

        Map<String, List<Map<String, Object>>> perProses = new LinkedHashMap<>();
        for (Periode p : Periode.values()) perProses.put(p.nama(), irisanProses(barisProses, p.nama(), terpilih));

        // "jobs" dan "successRate" tetap berbentuk seperti sebelum ada periode,
        // untuk klien yang sudah membacanya: angka ...Today selalu hari ini.
        Map<String, Object> angkaHariIni = perPeriode.get(Periode.TODAY.nama());

        Map<String, Object> pekerjaan = new LinkedHashMap<>();
        pekerjaan.put("running", angka(hitungan.get("running")));
        pekerjaan.put("pending", angka(hitungan.get("pending")));
        pekerjaan.put("stopping", angka(hitungan.get("stopping")));
        pekerjaan.put("successfulToday", angkaHariIni.get("successful"));
        pekerjaan.put("faultedToday", angkaHariIni.get("faulted"));
        pekerjaan.put("totalToday", angkaHariIni.get("total"));

        Map<String, Object> pustaka = ringkasan.hitunganPustaka(tenantId, folderId);

        Map<String, Object> antreanHitung = antrean.hitunganButir(tenantId, folderId);
        antreanHitung.put("total", pustaka == null ? 0L : angka(pustaka.get("queues")));

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("robots", robots.hitungan(tenantId, folderId));
        hasil.put("jobs", pekerjaan);
        hasil.put("periods", perPeriode);
        hasil.put("processBreakdown", perProses);
        hasil.put("queues", antreanHitung);
        hasil.put("library", pustaka);
        hasil.put("successRate", angkaHariIni.get("successRate"));
        hasil.put("unreadAlerts", catatan.belumDibaca(tenantId));
        hasil.put("jobsInProgress", ringkasan.sedangBerjalan(tenantId, folderId, BATAS_PANEL));
        hasil.put("activeRobots", robots.untukDasbor(tenantId, folderId, BATAS_PANEL));
        hasil.put("upcomingTriggers", pemicu.berikutnya(tenantId, folderId, BATAS_PEMICU));
        hasil.put("recentAlerts", catatan.peringatan(tenantId, false, BATAS_PERINGATAN));
        hasil.put("queueSummary", antrean.ringkasan(tenantId, folderId, BATAS_ANTREAN));
        hasil.put("serverTime", Db.nowText());

        return hasil;
    }

    /**
     * Proses yang mendapat irisan sendiri: paling banyak {@link #IRISAN_PROSES},
     * yang terbanyak sepanjang TAHUN ini — periode terlebar.
     *
     * <p>Anggotanya SAMA untuk keempat periode, dan urutannya juga. Dasbor
     * mewarnai irisan menurut urutan ini, jadi berganti dari Harian ke Bulanan
     * tidak mewarnai ulang proses yang sama dengan warna lain: warna mengikuti
     * prosesnya, bukan peringkatnya hari itu.
     */
    static List<String> prosesTerpilih(List<Map<String, Object>> baris) {
        return baris.stream()
                .filter(b -> angka(b.get("year")) > 0)
                .sorted(Comparator.<Map<String, Object>>comparingLong(b -> angka(b.get("year"))).reversed()
                        .thenComparing(b -> String.valueOf(b.get("processName")), String.CASE_INSENSITIVE_ORDER))
                .limit(IRISAN_PROSES)
                .map(b -> String.valueOf(b.get("processName")))
                .toList();
    }

    /**
     * Irisan donat per proses untuk satu periode, dalam urutan {@code terpilih}.
     *
     * <p>Proses terpilih tetap ikut walau jumlahnya nol di periode ini —
     * keterangan donatnya tidak berganti isi setiap kali periodenya diganti.
     * Proses lain digabung menjadi satu irisan "lainnya" ({@code other: true},
     * tanpa nama), hanya kalau jumlahnya lebih dari nol.
     */
    static List<Map<String, Object>> irisanProses(List<Map<String, Object>> baris, String periode,
                                                  List<String> terpilih) {
        Map<String, Long> jumlah = new LinkedHashMap<>();
        for (Map<String, Object> b : baris) jumlah.put(String.valueOf(b.get("processName")), angka(b.get(periode)));

        List<Map<String, Object>> hasil = new ArrayList<>();

        for (String nama : terpilih) {
            Map<String, Object> irisan = new LinkedHashMap<>();
            irisan.put("name", nama);
            irisan.put("count", jumlah.getOrDefault(nama, 0L));
            irisan.put("other", false);
            hasil.add(irisan);
        }

        long sisa = 0;
        int prosesSisa = 0;

        for (Map.Entry<String, Long> e : jumlah.entrySet()) {
            if (terpilih.contains(e.getKey()) || e.getValue() <= 0) continue;

            sisa += e.getValue();
            prosesSisa++;
        }

        if (sisa > 0) {
            Map<String, Object> lainnya = new LinkedHashMap<>();
            lainnya.put("name", null);
            lainnya.put("count", sisa);
            lainnya.put("other", true);
            lainnya.put("processes", prosesSisa);
            hasil.add(lainnya);
        }

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
        hasil.put("stopped", angka(hitungan.get(nama + "Stopped")));
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
    /**
     * @param akses folder yang boleh dilihat penanya, atau null untuk semua.
     *              Hasil dari folder lain dibuang: pencarian tidak boleh
     *              menjadi jalan melihat isi folder yang disembunyikan dari
     *              bilah folder.
     */
    public List<Map<String, Object>> cari(UUID tenantId, String q, Set<UUID> akses) {
        if (q == null || q.trim().length() < 2) return Db.kosong();

        List<Map<String, Object>> hasil = ringkasan.cari(tenantId, pola(q), BATAS_CARI);
        if (akses == null) return hasil;

        return hasil.stream()
                .filter(h -> h.get("folderId") == null || akses.contains(Db.uuid((String) h.get("folderId"))))
                .toList();
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
