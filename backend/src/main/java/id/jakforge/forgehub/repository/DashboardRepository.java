package id.jakforge.forgehub.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kueri yang hanya dipakai dasbor.
 *
 * <p>Terpisah dari repository lain dengan sengaja: bentuknya ditentukan oleh
 * apa yang muat di satu layar, bukan oleh tabelnya. Menaruh "sepuluh pekerjaan
 * teratas untuk kartu ringkasan" di dalam JobRepository membuat repository itu
 * berubah setiap kali tata letak dasbornya berubah.
 *
 * <p>Setiap kueri menerima {@code folderId}: dasbor menampilkan SATU folder.
 * null berarti seluruh penyewa — bentuk sebelum folder ada.
 */
@Repository
@RequiredArgsConstructor
public class DashboardRepository {

    private static final String UNFINISHED_STATES = "('PENDING', 'RUNNING', 'STOPPING')";

    /**
     * Awal hari, minggu (Senin), bulan, dan tahun ini — tengah malam di zona
     * tampilan, dinyatakan dalam UTC untuk dibandingkan dengan kolomnya.
     */
    public record PeriodStarts(OffsetDateTime today, OffsetDateTime week, OffsetDateTime month,
                               OffsetDateTime year) {
    }

    /**
     * Rentang satu grafik riwayat.
     *
     * @param bucketUnit       hour, day, atau month
     * @param firstBucketStart awal batang pertama, waktu setempat
     * @param lastBucketStart  awal batang TERAKHIR, waktu setempat (ikut dihitung)
     * @param fromUtc          batas bawah ended_at
     * @param untilUtc         batas atas ended_at, tidak ikut
     */
    public record HistoryRange(String bucketUnit, LocalDateTime firstBucketStart, LocalDateTime lastBucketStart,
                               OffsetDateTime fromUtc, OffsetDateTime untilUtc) {
    }

    private final Database database;

    /**
     * Hitungan pekerjaan: yang sedang berlangsung, lalu yang berhasil, gagal,
     * dan dibuat sejak awal hari, minggu, bulan, dan tahun ini — keempatnya
     * dalam SATU kueri.
     *
     * <p>Setiap kartu di dasbor memilih rentangnya sendiri. Menghitung semua
     * rentang sekaligus membuat berganti pilihan tidak menunggu permintaan
     * baru, dan kartu-kartu dengan rentang berbeda tetap berasal dari saat
     * yang sama.
     *
     * <p>Nama kolomnya {@code <periode>_<angka>} — today_successful,
     * week_faulted, dan seterusnya — mengikuti {@code DashboardPeriod.apiName()}.
     *
     * <p>Yang SELESAI dihitung menurut kapan ia selesai (ended_at), yang
     * dibuat menurut kapan ia dibuat. Yang masih berjalan, menunggu, atau
     * sedang dihentikan dihitung tanpa rentang: ia belum punya akhir, dan
     * menyembunyikannya karena dibuat kemarin berarti menyembunyikan yang
     * paling perlu dilihat.
     */
    public Map<String, Object> countJobs(UUID tenantId, UUID folderId, PeriodStarts starts) {
        List<Object> args = new ArrayList<>(List.of(starts.today(), starts.week(), starts.month(), starts.year(),
                tenantId));
        String folderFilter = "";

        if (folderId != null) {
            folderFilter = " AND j.folder_id = ?";
            args.add(folderId);
        }

        return database.queryRow("""
                WITH period_start AS (SELECT ?::timestamptz AS today_start, ?::timestamptz AS week_start,
                                             ?::timestamptz AS month_start, ?::timestamptz AS year_start)
                SELECT count(*) FILTER (WHERE j.state = 'RUNNING')  AS running,
                       count(*) FILTER (WHERE j.state = 'PENDING')  AS pending,
                       count(*) FILTER (WHERE j.state = 'STOPPING') AS stopping,
                       count(*) FILTER (WHERE j.state = 'SUCCESSFUL' AND j.ended_at >= s.today_start) AS today_successful,
                       count(*) FILTER (WHERE j.state = 'FAULTED'    AND j.ended_at >= s.today_start) AS today_faulted,
                       count(*) FILTER (WHERE j.state = 'STOPPED'    AND j.ended_at >= s.today_start) AS today_stopped,
                       count(*) FILTER (WHERE j.created_at >= s.today_start)                           AS today_total,
                       count(*) FILTER (WHERE j.state = 'SUCCESSFUL' AND j.ended_at >= s.week_start)  AS week_successful,
                       count(*) FILTER (WHERE j.state = 'FAULTED'    AND j.ended_at >= s.week_start)  AS week_faulted,
                       count(*) FILTER (WHERE j.state = 'STOPPED'    AND j.ended_at >= s.week_start)  AS week_stopped,
                       count(*) FILTER (WHERE j.created_at >= s.week_start)                            AS week_total,
                       count(*) FILTER (WHERE j.state = 'SUCCESSFUL' AND j.ended_at >= s.month_start) AS month_successful,
                       count(*) FILTER (WHERE j.state = 'FAULTED'    AND j.ended_at >= s.month_start) AS month_faulted,
                       count(*) FILTER (WHERE j.state = 'STOPPED'    AND j.ended_at >= s.month_start) AS month_stopped,
                       count(*) FILTER (WHERE j.created_at >= s.month_start)                           AS month_total,
                       count(*) FILTER (WHERE j.state = 'SUCCESSFUL' AND j.ended_at >= s.year_start)  AS year_successful,
                       count(*) FILTER (WHERE j.state = 'FAULTED'    AND j.ended_at >= s.year_start)  AS year_faulted,
                       count(*) FILTER (WHERE j.state = 'STOPPED'    AND j.ended_at >= s.year_start)  AS year_stopped,
                       count(*) FILTER (WHERE j.created_at >= s.year_start)                            AS year_total
                  FROM jobs j CROSS JOIN period_start s
                 WHERE j.tenant_id = ?%s
                """.formatted(folderFilter), args.toArray()).orElseGet(LinkedHashMap::new);
    }

    /**
     * Pekerjaan per proses untuk keempat periode sekaligus — satu baris per
     * proses, satu kolom per periode.
     *
     * <p>"Pekerjaan sebuah periode" di sini sama persis dengan yang dijumlah
     * {@link #countJobs}: yang selesai di dalam periodenya, ditambah yang belum
     * selesai. Dua donat di dasbor harus menunjukkan jumlah yang sama untuk
     * periode yang sama, atau orang mulai bertanya mana yang benar.
     */
    public List<Map<String, Object>> countJobsByProcess(UUID tenantId, UUID folderId, PeriodStarts starts) {
        List<Object> args = new ArrayList<>(List.of(starts.today(), starts.week(), starts.month(), starts.year(),
                tenantId));
        String folderFilter = "";

        if (folderId != null) {
            folderFilter = " AND j.folder_id = ?";
            args.add(folderId);
        }

        return database.queryRows("""
                WITH period_start AS (SELECT ?::timestamptz AS today_start, ?::timestamptz AS week_start,
                                             ?::timestamptz AS month_start, ?::timestamptz AS year_start)
                SELECT j.process_name,
                       count(*) FILTER (WHERE j.ended_at >= s.today_start OR j.state IN %1$s) AS today,
                       count(*) FILTER (WHERE j.ended_at >= s.week_start  OR j.state IN %1$s) AS week,
                       count(*) FILTER (WHERE j.ended_at >= s.month_start OR j.state IN %1$s) AS month,
                       count(*) FILTER (WHERE j.ended_at >= s.year_start  OR j.state IN %1$s) AS year
                  FROM jobs j CROSS JOIN period_start s
                 WHERE j.tenant_id = ?%2$s
                   AND (j.ended_at >= s.year_start OR j.state IN %1$s)
                 GROUP BY j.process_name
                """.formatted(UNFINISHED_STATES, folderFilter), args.toArray());
    }

    public List<Map<String, Object>> findJobsInProgress(UUID tenantId, UUID folderId, int limit) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String folderFilter = "";

        if (folderId != null) {
            folderFilter = " AND folder_id = ?";
            args.add(folderId);
        }

        args.add(limit);

        return database.queryRows("""
                SELECT id, process_name, robot_name, machine_name, state, source, priority,
                       progress, info, created_at, started_at
                  FROM jobs
                 WHERE tenant_id = ? AND state IN ('RUNNING', 'PENDING', 'STOPPING')%s
                 ORDER BY CASE state WHEN 'RUNNING' THEN 0 WHEN 'STOPPING' THEN 1 ELSE 2 END,
                          created_at
                 LIMIT ?
                """.formatted(folderFilter), args.toArray());
    }

    /**
     * Riwayat harian.
     *
     * <p>generate_series memastikan hari TANPA pekerjaan tetap muncul sebagai
     * nol. Tanpa itu grafiknya memampatkan hari kosong dan garisnya menyambung
     * dari Senin ke Kamis seolah tidak ada jeda.
     */
    public List<Map<String, Object>> findDailyHistory(UUID tenantId, String zoneId, int days) {
        return database.queryRows("""
                SELECT to_char(d.day_start, 'YYYY-MM-DD') AS day,
                       count(j.id) FILTER (WHERE j.state = 'SUCCESSFUL') AS successful,
                       count(j.id) FILTER (WHERE j.state = 'FAULTED')    AS faulted
                  FROM generate_series(
                           (now() AT TIME ZONE ?)::date - (? - 1),
                           (now() AT TIME ZONE ?)::date,
                           interval '1 day') AS d(day_start)
                  LEFT JOIN jobs j
                         ON j.tenant_id = ?
                        AND j.ended_at IS NOT NULL
                        AND (j.ended_at AT TIME ZONE ?)::date = d.day_start::date
                 GROUP BY d.day_start
                 ORDER BY d.day_start
                """, zoneId, days, zoneId, tenantId, zoneId);
    }

    /**
     * Riwayat satu periode, satu baris per batang.
     *
     * <p>Batangnya dibangkitkan generate_series dalam waktu SETEMPAT (zona
     * tampilan), lalu setiap pekerjaan dipotong ke satuan yang sama
     * — jam, hari, atau bulan — di zona itu juga. Batang tanpa pekerjaan tetap
     * muncul sebagai nol, dengan alasan yang sama seperti di
     * {@link #findDailyHistory}.
     *
     * <p>Rentang ended_at disaring LEBIH DULU, dengan batas UTC: tanpa itu
     * penggabungannya memotong setiap pekerjaan penyewa ini sepanjang masa
     * hanya untuk membuang hampir semuanya.
     */
    public List<Map<String, Object>> findPeriodHistory(UUID tenantId, String zoneId, HistoryRange range) {
        return database.queryRows("""
                SELECT to_char(b.bucket_start, 'YYYY-MM-DD"T"HH24:MI') AS bucket,
                       to_char(b.bucket_start, 'YYYY-MM-DD')          AS day,
                       count(j.id) FILTER (WHERE j.state = 'SUCCESSFUL') AS successful,
                       count(j.id) FILTER (WHERE j.state = 'FAULTED')    AS faulted
                  FROM generate_series(?::timestamp, ?::timestamp, ('1 ' || ?)::interval) AS b(bucket_start)
                  LEFT JOIN jobs j
                         ON j.tenant_id = ?
                        AND j.ended_at >= ? AND j.ended_at < ?
                        AND date_trunc(?, j.ended_at AT TIME ZONE ?) = b.bucket_start
                 GROUP BY b.bucket_start
                 ORDER BY b.bucket_start
                """, range.firstBucketStart(), range.lastBucketStart(), range.bucketUnit(), tenantId,
                range.fromUtc(), range.untilUtc(), range.bucketUnit(), zoneId);
    }

    /**
     * Pencarian menyeluruh.
     *
     * <p>ILIKE, bukan LIKE: orang mencari "cha" dan berharap menemukan "CHA".
     * Tanda % dan _ pada kata kuncinya diloloskan oleh pemanggilnya, supaya
     * mencari "100%" tidak berubah menjadi "cocokkan apa saja".
     *
     * <p>{@code folder_id} ikut dikirim untuk yang tinggal di folder: memilih
     * hasilnya berarti membuka folder itu lebih dulu, lalu halamannya. Robot
     * dan paket milik penyewa, jadi foldernya kosong.
     *
     * <p>Kolom {@code kind} adalah label yang DITAMPILKAN dasbor apa adanya,
     * dan {@code page} sekaligus nama sumber izinnya.
     */
    public List<Map<String, Object>> search(UUID tenantId, String pattern, int limit) {
        return database.queryRows("""
                SELECT 'Proses' AS kind, name AS label, COALESCE(description, '') AS detail,
                       'processes' AS page, folder_id
                  FROM processes WHERE tenant_id = ? AND name ILIKE ?
                UNION ALL
                SELECT 'Robot', name, COALESCE(machine_name, ''), 'robots', NULL::uuid
                  FROM robots WHERE tenant_id = ? AND name ILIKE ?
                UNION ALL
                SELECT 'Antrean', name, COALESCE(description, ''), 'queues', folder_id
                  FROM queues WHERE tenant_id = ? AND name ILIKE ?
                UNION ALL
                SELECT 'Aset', name, COALESCE(description, ''), 'assets', folder_id
                  FROM assets WHERE tenant_id = ? AND name ILIKE ?
                UNION ALL
                SELECT 'Pemicu', name, process_name, 'triggers', folder_id
                  FROM triggers WHERE tenant_id = ? AND name ILIKE ?
                UNION ALL
                SELECT 'Paket', name || ' ' || version, COALESCE(description, ''), 'packages', NULL::uuid
                  FROM packages WHERE tenant_id = ? AND name ILIKE ?
                UNION ALL
                SELECT 'Ember Penyimpanan', name, COALESCE(description, ''), 'buckets', folder_id
                  FROM buckets WHERE tenant_id = ? AND name ILIKE ?
                UNION ALL
                SELECT 'Folder', name, COALESCE(description, ''), 'folders', id
                  FROM folders WHERE tenant_id = ? AND owner_id IS NULL AND name ILIKE ?
                LIMIT ?
                """, tenantId, pattern, tenantId, pattern, tenantId, pattern, tenantId, pattern,
                     tenantId, pattern, tenantId, pattern, tenantId, pattern, tenantId, pattern, limit);
    }

    /** Jumlah isi tiap tabel; dipakai halaman Setelan. */
    public Map<String, Object> countTableRows(UUID tenantId) {
        return database.queryRow("""
                SELECT (SELECT count(*) FROM users     WHERE tenant_id = ?) AS users,
                       (SELECT count(*) FROM robots    WHERE tenant_id = ?) AS robots,
                       (SELECT count(*) FROM processes WHERE tenant_id = ?) AS processes,
                       (SELECT count(*) FROM jobs      WHERE tenant_id = ?) AS jobs,
                       (SELECT count(*) FROM logs      WHERE tenant_id = ?) AS logs
                """, tenantId, tenantId, tenantId, tenantId, tenantId).orElseGet(LinkedHashMap::new);
    }

    /**
     * Angka kartu di baris atas dasbor: proses, aset, antrean, pemicu,
     * pengguna, dan mesin — ditambah paket, ember, dan robot.
     *
     * <p>Untuk sebuah folder, pengguna dan mesin berarti yang DITUGASKAN ke
     * sana: pengguna yang boleh melihatnya, dan mesin tempat robot-robotnya
     * berjalan. Folder Saya selalu punya satu pengguna — pemiliknya. Tanpa
     * folder, angka keduanya milik seluruh penyewa.
     *
     * <p>Folder dan penyewa dikirim SEKALI lewat CTE, bukan diulang untuk
     * setiap subkueri: dua puluh tanda tanya yang harus urut adalah tempat
     * paling mudah untuk salah pasang.
     */
    public Map<String, Object> countLibrary(UUID tenantId, UUID folderId) {
        return database.queryRow("""
                WITH t AS (SELECT ?::uuid AS id), f AS (SELECT ?::uuid AS id)
                SELECT (SELECT count(*) FROM processes x, t, f
                         WHERE x.tenant_id = t.id AND (f.id IS NULL OR x.folder_id = f.id)) AS processes,
                       (SELECT count(*) FROM assets x, t, f
                         WHERE x.tenant_id = t.id AND (f.id IS NULL OR x.folder_id = f.id)) AS assets,
                       (SELECT count(*) FROM queues x, t, f
                         WHERE x.tenant_id = t.id AND (f.id IS NULL OR x.folder_id = f.id)) AS queues,
                       (SELECT count(*) FROM triggers x, t, f
                         WHERE x.tenant_id = t.id AND (f.id IS NULL OR x.folder_id = f.id)) AS triggers,
                       (SELECT count(*) FROM triggers x, t, f
                         WHERE x.tenant_id = t.id AND (f.id IS NULL OR x.folder_id = f.id)
                           AND x.enabled) AS triggers_enabled,
                       (SELECT count(*) FROM buckets x, t, f
                         WHERE x.tenant_id = t.id AND (f.id IS NULL OR x.folder_id = f.id)) AS buckets,
                       (SELECT count(*) FROM packages x, t, f
                         WHERE x.tenant_id = t.id
                           AND (f.id IS NULL OR x.name IN (SELECT p.package_name FROM processes p
                                                           WHERE p.tenant_id = t.id
                                                             AND p.folder_id = f.id))) AS packages,
                       (SELECT CASE WHEN f.id IS NULL
                                    THEN (SELECT count(*) FROM users u WHERE u.tenant_id = t.id)
                                    ELSE (SELECT count(*) FROM folder_users fu WHERE fu.folder_id = f.id)
                                         + (SELECT count(*) FROM folders o
                                             WHERE o.id = f.id AND o.owner_id IS NOT NULL)
                               END FROM t, f) AS users,
                       (SELECT CASE WHEN f.id IS NULL
                                    THEN (SELECT count(*) FROM robots r WHERE r.tenant_id = t.id)
                                    ELSE (SELECT count(*) FROM folder_robots fr WHERE fr.folder_id = f.id)
                               END FROM t, f) AS robots,
                       (SELECT CASE WHEN f.id IS NULL
                                    THEN (SELECT count(*) FROM machines m WHERE m.tenant_id = t.id)
                                    ELSE (SELECT count(DISTINCT r.machine_name)
                                            FROM folder_robots fr JOIN robots r ON r.id = fr.robot_id
                                           WHERE fr.folder_id = f.id
                                             AND r.machine_name IS NOT NULL AND r.machine_name <> '')
                               END FROM t, f) AS machines
                """, tenantId, folderId).orElseGet(LinkedHashMap::new);
    }
}
