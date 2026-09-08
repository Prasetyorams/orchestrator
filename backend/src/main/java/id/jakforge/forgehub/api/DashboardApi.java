package id.jakforge.forgehub.api;

import id.jakforge.forgehub.data.Db;
import id.jakforge.forgehub.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Halaman utama dan pencarian menyeluruh.
 *
 * <p>Seluruh isi dasbor diambil dalam SATU permintaan. Dasbor menyegarkan
 * dirinya tiap beberapa detik; sepuluh permintaan terpisah berarti sepuluh kali
 * perjalanan ke basis data tiap penyegaran, dan angka-angkanya bisa berasal
 * dari sepuluh saat yang berbeda — kartu "berjalan" tidak cocok dengan tabel di
 * bawahnya.
 *
 * <p>Versi .NET menulis alasan itu di komentarnya lalu tetap menjalankan sekitar
 * dua puluh COUNT terpisah. Di sini tiap kelompok angka diambil dengan SATU
 * kueri memakai {@code count(*) FILTER (WHERE ...)}, sehingga angka-angka dalam
 * satu kartu benar-benar berasal dari satu saat yang sama.
 */
@RestController
@RequestMapping("/api")
public class DashboardApi {

    private static final int PUTUS_SETELAH_DETIK = 45;

    private final Db db;
    private final ZoneId zonaTampilan;

    /**
     * @param zonaTampilan zona untuk menghitung batas "hari ini".
     *
     * <p>Bukan zona server. Di dalam container, zona server adalah UTC, dan
     * orang yang melihat dasbor pukul 7 pagi WIB akan melihat angka "hari ini"
     * yang masih menghitung kemarin sore. Versi .NET memakai
     * {@code DateTime.Today} dengan maksud yang benar, tapi maksud itu hilang
     * begitu programnya dijalankan di dalam container.
     */
    public DashboardApi(Db db, @Value("${forgehub.display-timezone:UTC}") String zonaTampilan) {
        this.db = db;
        this.zonaTampilan = Cron.zona(zonaTampilan);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dasbor() {
        UUID tenantId = CurrentUser.get().tenantId();

        OffsetDateTime awalHari = LocalDate.now(zonaTampilan)
                .atStartOfDay(zonaTampilan)
                .toOffsetDateTime()
                .withOffsetSameInstant(ZoneOffset.UTC);

        Map<String, Object> robots = db.row("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'AVAILABLE' AND segar) AS available,
                       count(*) FILTER (WHERE status = 'BUSY'      AND segar) AS busy,
                       count(*) FILTER (WHERE NOT segar)                      AS disconnected
                  FROM (SELECT status,
                               last_heartbeat_at IS NOT NULL
                               AND now() - last_heartbeat_at <= interval '%d seconds' AS segar
                          FROM robots WHERE tenant_id = ?) AS r
                """.formatted(PUTUS_SETELAH_DETIK), tenantId);

        Map<String, Object> jobs = db.row("""
                SELECT count(*) FILTER (WHERE state = 'RUNNING')  AS running,
                       count(*) FILTER (WHERE state = 'PENDING')  AS pending,
                       count(*) FILTER (WHERE state = 'SUCCESSFUL' AND ended_at >= ?)  AS successful_today,
                       count(*) FILTER (WHERE state = 'FAULTED'    AND ended_at >= ?)  AS faulted_today,
                       count(*) FILTER (WHERE created_at >= ?)                          AS total_today
                  FROM jobs WHERE tenant_id = ?
                """, awalHari, awalHari, awalHari, tenantId);

        Map<String, Object> queues = db.row("""
                SELECT (SELECT count(*) FROM queues WHERE tenant_id = ?) AS total,
                       count(*) FILTER (WHERE status = 'NEW')         AS new_items,
                       count(*) FILTER (WHERE status = 'IN_PROGRESS') AS in_progress,
                       count(*) FILTER (WHERE status = 'FAILED')      AS failed
                  FROM queue_items WHERE tenant_id = ?
                """, tenantId, tenantId);

        Map<String, Object> library = db.row("""
                SELECT (SELECT count(*) FROM processes WHERE tenant_id = ?) AS processes,
                       (SELECT count(*) FROM packages  WHERE tenant_id = ?) AS packages,
                       (SELECT count(*) FROM assets    WHERE tenant_id = ?) AS assets,
                       (SELECT count(*) FROM machines  WHERE tenant_id = ?) AS machines,
                       (SELECT count(*) FROM triggers  WHERE tenant_id = ? AND enabled) AS triggers
                """, tenantId, tenantId, tenantId, tenantId, tenantId);

        long berhasil = angka(jobs.get("successfulToday"));
        long gagal = angka(jobs.get("faultedToday"));
        long selesai = berhasil + gagal;

        // Tingkat keberhasilan dihitung dari pekerjaan yang sudah SELESAI saja.
        // Memasukkan yang masih berjalan ke penyebut membuat angkanya turun
        // tiap kali pekerjaan baru dimulai, seolah ada yang baru saja gagal.
        double tingkatBerhasil = selesai == 0
                ? 100.0
                : Math.round(berhasil * 1000.0 / selesai) / 10.0;

        List<Map<String, Object>> sedangJalan = db.rows("""
                SELECT id, process_name, robot_name, machine_name, state, source, priority,
                       progress, info, created_at, started_at
                  FROM jobs
                 WHERE tenant_id = ? AND state IN ('RUNNING', 'PENDING', 'STOPPING')
                 ORDER BY CASE state WHEN 'RUNNING' THEN 0 WHEN 'STOPPING' THEN 1 ELSE 2 END,
                          created_at
                 LIMIT 25
                """, tenantId);

        List<Map<String, Object>> robotAktif = db.rows("""
                SELECT r.name, r.machine_name, r.type, r.environment, r.cpu_percent, r.memory_mb,
                       r.last_heartbeat_at,
                       CASE WHEN r.last_heartbeat_at IS NULL
                              OR now() - r.last_heartbeat_at > interval '%d seconds'
                            THEN 'DISCONNECTED' ELSE r.status END AS status,
                       (SELECT count(*) FROM jobs j
                         WHERE j.tenant_id = r.tenant_id AND j.robot_name = r.name
                           AND j.state = 'RUNNING') AS running_jobs
                  FROM robots r
                 WHERE r.tenant_id = ?
                 ORDER BY CASE WHEN r.last_heartbeat_at IS NULL
                                 OR now() - r.last_heartbeat_at > interval '%d seconds'
                               THEN 2 ELSE 0 END,
                          r.name
                 LIMIT 25
                """.formatted(PUTUS_SETELAH_DETIK, PUTUS_SETELAH_DETIK), tenantId);

        List<Map<String, Object>> pemicuBerikutnya = db.rows("""
                SELECT name, process_name, type, interval_minutes, cron, timezone,
                       enabled, next_run_at, last_run_at
                  FROM triggers
                 WHERE tenant_id = ? AND enabled
                 ORDER BY next_run_at
                 LIMIT 10
                """, tenantId);

        List<Map<String, Object>> peringatanTerbaru = db.rows("""
                SELECT id, severity, title, message, source, is_read, created_at
                  FROM alerts WHERE tenant_id = ?
                 ORDER BY id DESC LIMIT 8
                """, tenantId);

        long belumDibaca = db.count(
                "SELECT count(*) FROM alerts WHERE tenant_id = ? AND NOT is_read", tenantId);

        List<Map<String, Object>> ringkasanAntrean = db.rows("""
                SELECT q.name,
                       count(*) FILTER (WHERE i.status = 'NEW')         AS new_count,
                       count(*) FILTER (WHERE i.status = 'IN_PROGRESS') AS in_progress_count,
                       count(*) FILTER (WHERE i.status = 'SUCCESSFUL')  AS successful_count,
                       count(*) FILTER (WHERE i.status = 'FAILED')      AS failed_count
                  FROM queues q
                  LEFT JOIN queue_items i
                         ON i.tenant_id = q.tenant_id AND i.queue_name = q.name
                 WHERE q.tenant_id = ?
                 GROUP BY q.name
                 ORDER BY q.name
                 LIMIT 8
                """, tenantId);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("robots", robots);
        hasil.put("jobs", jobs);
        hasil.put("queues", queues);
        hasil.put("library", library);
        hasil.put("successRate", tingkatBerhasil);
        hasil.put("unreadAlerts", belumDibaca);
        hasil.put("jobsInProgress", sedangJalan);
        hasil.put("activeRobots", robotAktif);
        hasil.put("upcomingTriggers", pemicuBerikutnya);
        hasil.put("recentAlerts", peringatanTerbaru);
        hasil.put("queueSummary", ringkasanAntrean);
        hasil.put("serverTime", Db.nowText());

        return hasil;
    }

    /** Riwayat 14 hari untuk grafik ringkas. */
    @GetMapping("/dashboard/history")
    public List<Map<String, Object>> riwayat() {
        UUID tenantId = CurrentUser.get().tenantId();

        // generate_series memastikan hari TANPA pekerjaan tetap muncul sebagai
        // nol. Tanpa itu grafiknya memampatkan hari kosong dan garisnya
        // menyambung dari Senin ke Kamis seolah tidak ada jeda.
        return db.rows("""
                SELECT to_char(h.hari, 'YYYY-MM-DD') AS day,
                       count(j.id) FILTER (WHERE j.state = 'SUCCESSFUL') AS successful,
                       count(j.id) FILTER (WHERE j.state = 'FAULTED')    AS faulted
                  FROM generate_series(
                           (now() AT TIME ZONE ?)::date - 13,
                           (now() AT TIME ZONE ?)::date,
                           interval '1 day') AS h(hari)
                  LEFT JOIN jobs j
                         ON j.tenant_id = ?
                        AND j.ended_at IS NOT NULL
                        AND (j.ended_at AT TIME ZONE ?)::date = h.hari::date
                 GROUP BY h.hari
                 ORDER BY h.hari
                """, zonaTampilan.getId(), zonaTampilan.getId(), tenantId, zonaTampilan.getId());
    }

    /** Pencarian menyeluruh untuk kotak di kepala halaman. */
    @GetMapping("/search")
    public List<Map<String, Object>> cari(@RequestParam(name = "q", required = false) String q) {
        if (q == null || q.trim().length() < 2) return Db.kosong();

        UUID tenantId = CurrentUser.get().tenantId();

        // ILIKE, bukan LIKE: orang mencari "cha" dan berharap menemukan "CHA".
        // Tanda % dan _ di dalam kata kunci diloloskan supaya pencarian "100%"
        // tidak berubah menjadi "cocokkan apa saja".
        String pola = "%" + q.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";

        return db.rows("""
                SELECT 'Proses' AS kind, name AS label, COALESCE(description, '') AS detail,
                       'processes' AS page
                  FROM processes WHERE tenant_id = ? AND name ILIKE ?
                UNION ALL
                SELECT 'Robot', name, COALESCE(machine_name, ''), 'robots'
                  FROM robots WHERE tenant_id = ? AND name ILIKE ?
                UNION ALL
                SELECT 'Antrean', name, COALESCE(description, ''), 'queues'
                  FROM queues WHERE tenant_id = ? AND name ILIKE ?
                UNION ALL
                SELECT 'Aset', name, COALESCE(description, ''), 'assets'
                  FROM assets WHERE tenant_id = ? AND name ILIKE ?
                UNION ALL
                SELECT 'Pemicu', name, process_name, 'triggers'
                  FROM triggers WHERE tenant_id = ? AND name ILIKE ?
                UNION ALL
                SELECT 'Paket', name || ' ' || version, COALESCE(description, ''), 'packages'
                  FROM packages WHERE tenant_id = ? AND name ILIKE ?
                LIMIT 30
                """, tenantId, pola, tenantId, pola, tenantId, pola,
                     tenantId, pola, tenantId, pola, tenantId, pola);
    }

    private static long angka(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
