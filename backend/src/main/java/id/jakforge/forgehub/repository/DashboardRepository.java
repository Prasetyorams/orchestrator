package id.jakforge.forgehub.repository;

import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
 */
@Repository
public class DashboardRepository {

    private final Db db;

    public DashboardRepository(Db db) {
        this.db = db;
    }

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
     * week_faulted, dan seterusnya — mengikuti {@code Periode.nama()}.
     *
     * @param hari   awal hari ini, UTC
     * @param minggu awal minggu ini (Senin), UTC
     * @param bulan  awal bulan ini, UTC
     * @param tahun  awal tahun ini, UTC
     */
    public Map<String, Object> hitunganPekerjaan(UUID tenantId, OffsetDateTime hari, OffsetDateTime minggu,
                                                 OffsetDateTime bulan, OffsetDateTime tahun) {
        return db.row("""
                WITH awal AS (SELECT ?::timestamptz AS hari,  ?::timestamptz AS minggu,
                                     ?::timestamptz AS bulan, ?::timestamptz AS tahun)
                SELECT count(*) FILTER (WHERE j.state = 'RUNNING') AS running,
                       count(*) FILTER (WHERE j.state = 'PENDING') AS pending,
                       count(*) FILTER (WHERE j.state = 'SUCCESSFUL' AND j.ended_at >= a.hari)   AS today_successful,
                       count(*) FILTER (WHERE j.state = 'FAULTED'    AND j.ended_at >= a.hari)   AS today_faulted,
                       count(*) FILTER (WHERE j.created_at >= a.hari)                             AS today_total,
                       count(*) FILTER (WHERE j.state = 'SUCCESSFUL' AND j.ended_at >= a.minggu) AS week_successful,
                       count(*) FILTER (WHERE j.state = 'FAULTED'    AND j.ended_at >= a.minggu) AS week_faulted,
                       count(*) FILTER (WHERE j.created_at >= a.minggu)                           AS week_total,
                       count(*) FILTER (WHERE j.state = 'SUCCESSFUL' AND j.ended_at >= a.bulan)  AS month_successful,
                       count(*) FILTER (WHERE j.state = 'FAULTED'    AND j.ended_at >= a.bulan)  AS month_faulted,
                       count(*) FILTER (WHERE j.created_at >= a.bulan)                            AS month_total,
                       count(*) FILTER (WHERE j.state = 'SUCCESSFUL' AND j.ended_at >= a.tahun)  AS year_successful,
                       count(*) FILTER (WHERE j.state = 'FAULTED'    AND j.ended_at >= a.tahun)  AS year_faulted,
                       count(*) FILTER (WHERE j.created_at >= a.tahun)                            AS year_total
                  FROM jobs j CROSS JOIN awal a
                 WHERE j.tenant_id = ?
                """, hari, minggu, bulan, tahun, tenantId);
    }

    public List<Map<String, Object>> sedangBerjalan(UUID tenantId, int batas) {
        return db.rows("""
                SELECT id, process_name, robot_name, machine_name, state, source, priority,
                       progress, info, created_at, started_at
                  FROM jobs
                 WHERE tenant_id = ? AND state IN ('RUNNING', 'PENDING', 'STOPPING')
                 ORDER BY CASE state WHEN 'RUNNING' THEN 0 WHEN 'STOPPING' THEN 1 ELSE 2 END,
                          created_at
                 LIMIT ?
                """, tenantId, batas);
    }

    /**
     * Riwayat harian.
     *
     * <p>generate_series memastikan hari TANPA pekerjaan tetap muncul sebagai
     * nol. Tanpa itu grafiknya memampatkan hari kosong dan garisnya menyambung
     * dari Senin ke Kamis seolah tidak ada jeda.
     */
    public List<Map<String, Object>> riwayat(UUID tenantId, String zona, int hari) {
        return db.rows("""
                SELECT to_char(h.hari, 'YYYY-MM-DD') AS day,
                       count(j.id) FILTER (WHERE j.state = 'SUCCESSFUL') AS successful,
                       count(j.id) FILTER (WHERE j.state = 'FAULTED')    AS faulted
                  FROM generate_series(
                           (now() AT TIME ZONE ?)::date - (? - 1),
                           (now() AT TIME ZONE ?)::date,
                           interval '1 day') AS h(hari)
                  LEFT JOIN jobs j
                         ON j.tenant_id = ?
                        AND j.ended_at IS NOT NULL
                        AND (j.ended_at AT TIME ZONE ?)::date = h.hari::date
                 GROUP BY h.hari
                 ORDER BY h.hari
                """, zona, hari, zona, tenantId, zona);
    }

    /**
     * Riwayat satu periode, satu baris per batang.
     *
     * <p>Batangnya dibangkitkan generate_series dalam waktu SETEMPAT (zona
     * tampilan), lalu setiap pekerjaan dipotong ke satuan yang sama
     * — jam, hari, atau bulan — di zona itu juga. Batang tanpa pekerjaan tetap
     * muncul sebagai nol, dengan alasan yang sama seperti di {@link #riwayat}.
     *
     * <p>Rentang ended_at disaring LEBIH DULU, dengan batas UTC: tanpa itu
     * penggabungannya memotong setiap pekerjaan penyewa ini sepanjang masa
     * hanya untuk membuang hampir semuanya.
     *
     * @param satuan    hour, day, atau month
     * @param awal      awal batang pertama, waktu setempat
     * @param akhir     awal batang TERAKHIR, waktu setempat (ikut dihitung)
     * @param awalUtc   batas bawah ended_at
     * @param akhirUtc  batas atas ended_at, tidak ikut
     */
    public List<Map<String, Object>> riwayatPeriode(UUID tenantId, String zona, String satuan,
                                                    LocalDateTime awal, LocalDateTime akhir,
                                                    OffsetDateTime awalUtc, OffsetDateTime akhirUtc) {
        return db.rows("""
                SELECT to_char(h.t, 'YYYY-MM-DD"T"HH24:MI') AS bucket,
                       to_char(h.t, 'YYYY-MM-DD')          AS day,
                       count(j.id) FILTER (WHERE j.state = 'SUCCESSFUL') AS successful,
                       count(j.id) FILTER (WHERE j.state = 'FAULTED')    AS faulted
                  FROM generate_series(?::timestamp, ?::timestamp, ('1 ' || ?)::interval) AS h(t)
                  LEFT JOIN jobs j
                         ON j.tenant_id = ?
                        AND j.ended_at >= ? AND j.ended_at < ?
                        AND date_trunc(?, j.ended_at AT TIME ZONE ?) = h.t
                 GROUP BY h.t
                 ORDER BY h.t
                """, awal, akhir, satuan, tenantId, awalUtc, akhirUtc, satuan, zona);
    }

    /**
     * Pencarian menyeluruh.
     *
     * <p>ILIKE, bukan LIKE: orang mencari "cha" dan berharap menemukan "CHA".
     * Tanda % dan _ pada kata kuncinya diloloskan oleh pemanggilnya, supaya
     * mencari "100%" tidak berubah menjadi "cocokkan apa saja".
     */
    public List<Map<String, Object>> cari(UUID tenantId, String pola, int batas) {
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
                LIMIT ?
                """, tenantId, pola, tenantId, pola, tenantId, pola,
                     tenantId, pola, tenantId, pola, tenantId, pola, batas);
    }

    /** Jumlah isi tiap tabel; dipakai halaman Setelan. */
    public Map<String, Object> isiBasisData(UUID tenantId) {
        return db.row("""
                SELECT (SELECT count(*) FROM users     WHERE tenant_id = ?) AS users,
                       (SELECT count(*) FROM robots    WHERE tenant_id = ?) AS robots,
                       (SELECT count(*) FROM processes WHERE tenant_id = ?) AS processes,
                       (SELECT count(*) FROM jobs      WHERE tenant_id = ?) AS jobs,
                       (SELECT count(*) FROM logs      WHERE tenant_id = ?) AS logs
                """, tenantId, tenantId, tenantId, tenantId, tenantId);
    }

    /** Jumlah proses, paket, aset, dan mesin; dipakai kartu "pustaka" di dasbor. */
    public Map<String, Object> hitunganPustaka(UUID tenantId) {
        return db.row("""
                SELECT (SELECT count(*) FROM processes WHERE tenant_id = ?) AS processes,
                       (SELECT count(*) FROM packages  WHERE tenant_id = ?) AS packages,
                       (SELECT count(*) FROM assets    WHERE tenant_id = ?) AS assets,
                       (SELECT count(*) FROM machines  WHERE tenant_id = ?) AS machines,
                       (SELECT count(*) FROM triggers  WHERE tenant_id = ? AND enabled) AS triggers
                """, tenantId, tenantId, tenantId, tenantId, tenantId);
    }
}
