package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.Cron;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.DashboardRepository;
import id.jakforge.forgehub.repository.LogRepository;
import id.jakforge.forgehub.repository.QueueRepository;
import id.jakforge.forgehub.repository.RobotRepository;
import id.jakforge.forgehub.repository.TriggerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

    /**
     * @param zonaTampilan zona untuk menghitung batas "hari ini".
     *
     * <p>Bukan zona server. Di dalam container zona server adalah UTC, dan orang
     * yang membuka dasbor pukul 7 pagi WIB akan melihat angka "hari ini" yang
     * masih menghitung kemarin sore.
     */
    public DashboardService(DashboardRepository ringkasan, RobotRepository robots,
                            QueueRepository antrean, TriggerRepository pemicu,
                            LogRepository catatan,
                            @Value("${forgehub.display-timezone:UTC}") String zonaTampilan) {
        this.ringkasan = ringkasan;
        this.robots = robots;
        this.antrean = antrean;
        this.pemicu = pemicu;
        this.catatan = catatan;
        this.zonaTampilan = Cron.zona(zonaTampilan);
    }

    public Map<String, Object> dasbor(UUID tenantId) {
        OffsetDateTime awalHari = LocalDate.now(zonaTampilan)
                .atStartOfDay(zonaTampilan)
                .toOffsetDateTime()
                .withOffsetSameInstant(ZoneOffset.UTC);

        Map<String, Object> pekerjaan = ringkasan.hitunganPekerjaan(tenantId, awalHari);

        Map<String, Object> antreanHitung = antrean.hitunganButir(tenantId);
        antreanHitung.put("total", antrean.jumlahAntrean(tenantId));

        long berhasil = angka(pekerjaan.get("successfulToday"));
        long gagal = angka(pekerjaan.get("faultedToday"));
        long selesai = berhasil + gagal;

        // Tingkat keberhasilan dihitung dari pekerjaan yang sudah SELESAI saja.
        // Memasukkan yang masih berjalan ke penyebut membuat angkanya turun
        // tiap kali pekerjaan baru dimulai, seolah ada yang baru saja gagal.
        double tingkat = selesai == 0 ? 100.0 : Math.round(berhasil * 1000.0 / selesai) / 10.0;

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("robots", robots.hitungan(tenantId));
        hasil.put("jobs", pekerjaan);
        hasil.put("queues", antreanHitung);
        hasil.put("library", ringkasan.hitunganPustaka(tenantId));
        hasil.put("successRate", tingkat);
        hasil.put("unreadAlerts", catatan.belumDibaca(tenantId));
        hasil.put("jobsInProgress", ringkasan.sedangBerjalan(tenantId, BATAS_PANEL));
        hasil.put("activeRobots", robots.untukDasbor(tenantId, BATAS_PANEL));
        hasil.put("upcomingTriggers", pemicu.berikutnya(tenantId, BATAS_PEMICU));
        hasil.put("recentAlerts", catatan.peringatan(tenantId, false, BATAS_PERINGATAN));
        hasil.put("queueSummary", antrean.ringkasan(tenantId, BATAS_ANTREAN));
        hasil.put("serverTime", Db.nowText());

        return hasil;
    }

    public List<Map<String, Object>> riwayat(UUID tenantId) {
        return ringkasan.riwayat(tenantId, zonaTampilan.getId(), HARI_RIWAYAT);
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
