package id.jakforge.forgehub.repository;

import id.jakforge.forgehub.model.QueueItemStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Akses data antrean transaksi dan butir-butirnya. */
@Repository
public class QueueRepository {

    private final Db db;

    public QueueRepository(Db db) {
        this.db = db;
    }

    // -----------------------------------------------------------------
    // Antrean
    // -----------------------------------------------------------------

    /**
     * Daftar antrean dengan hitungan tiap keadaan.
     *
     * <p>LEFT JOIN + FILTER, bukan lima subkueri: satu pemindaian tabel butir
     * alih-alih lima, dan seluruh angka dalam satu baris berasal dari satu saat
     * yang sama.
     */
    public List<Map<String, Object>> semua(UUID tenantId) {
        return db.rows("""
                SELECT q.id, q.name, q.description, q.max_retries, q.accept_duplicates, q.created_at,
                       count(*) FILTER (WHERE i.status = 'NEW')         AS new_count,
                       count(*) FILTER (WHERE i.status = 'IN_PROGRESS') AS in_progress_count,
                       count(*) FILTER (WHERE i.status = 'SUCCESSFUL')  AS successful_count,
                       count(*) FILTER (WHERE i.status = 'FAILED')      AS failed_count,
                       count(i.id)                                      AS total_count
                  FROM queues q
                  LEFT JOIN queue_items i
                         ON i.tenant_id = q.tenant_id AND i.queue_name = q.name
                 WHERE q.tenant_id = ?
                 GROUP BY q.id, q.name, q.description, q.max_retries, q.accept_duplicates, q.created_at
                 ORDER BY q.name
                """, tenantId);
    }

    /** Ringkasan untuk dasbor: tanpa kolom setelan, dan dibatasi. */
    public List<Map<String, Object>> ringkasan(UUID tenantId, int batas) {
        return db.rows("""
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
                 LIMIT ?
                """, tenantId, batas);
    }

    public Map<String, Object> satu(UUID tenantId, String nama) {
        return db.row("SELECT accept_duplicates, max_retries FROM queues WHERE tenant_id = ? AND name = ?",
                tenantId, nama);
    }

    public boolean ada(UUID tenantId, String nama) {
        return db.exists("SELECT count(*) FROM queues WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    public void buat(UUID tenantId, String nama, String keterangan, int maksPercobaan, boolean bolehKembar) {
        db.exec("""
                INSERT INTO queues (id, tenant_id, name, description, max_retries, accept_duplicates, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, Db.newId(), tenantId, nama, keterangan, maksPercobaan, bolehKembar);
    }

    public int hapus(UUID tenantId, String nama) {
        return db.exec("DELETE FROM queues WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    public void hapusButirAntrean(UUID tenantId, String nama) {
        db.exec("DELETE FROM queue_items WHERE tenant_id = ? AND queue_name = ?", tenantId, nama);
    }

    public long jumlahAntrean(UUID tenantId) {
        return db.count("SELECT count(*) FROM queues WHERE tenant_id = ?", tenantId);
    }

    public Map<String, Object> hitunganButir(UUID tenantId) {
        return db.row("""
                SELECT count(*) FILTER (WHERE status = 'NEW')         AS new_items,
                       count(*) FILTER (WHERE status = 'IN_PROGRESS') AS in_progress,
                       count(*) FILTER (WHERE status = 'FAILED')      AS failed
                  FROM queue_items WHERE tenant_id = ?
                """, tenantId);
    }

    // -----------------------------------------------------------------
    // Butir
    // -----------------------------------------------------------------

    /**
     * Butir sebuah antrean, boleh disaring keadaannya.
     *
     * <p>Satu kueri untuk kedua kemungkinan: penyaring null dilewati oleh
     * "? IS NULL". Dua kueri yang hampir sama adalah dua tempat yang harus
     * diubah bersamaan setiap kali kolomnya bertambah.
     */
    public List<Map<String, Object>> butir(UUID tenantId, String antrean, String status, int batas) {
        return db.rows("""
                SELECT id, reference, priority, status, content, output, exception, retries,
                       robot_name, created_at, started_at, ended_at
                  FROM queue_items
                 WHERE tenant_id = ? AND queue_name = ?
                   AND (?::text IS NULL OR status = ?::text)
                 ORDER BY created_at DESC
                 LIMIT ?
                """, tenantId, antrean, status, status, batas);
    }

    public Map<String, Object> satuButir(UUID tenantId, UUID id) {
        return db.row("""
                SELECT queue_name, retries, reference FROM queue_items
                 WHERE tenant_id = ? AND id = ?
                """, tenantId, id);
    }

    public boolean adaKembar(UUID tenantId, String antrean, String referensi) {
        return db.exists("""
                SELECT count(*) FROM queue_items
                 WHERE tenant_id = ? AND queue_name = ? AND reference = ?
                   AND status IN ('NEW', 'IN_PROGRESS')
                """, tenantId, antrean, referensi);
    }

    public void tambahButir(UUID id, UUID tenantId, String antrean, String referensi,
                            String prioritas, String isi) {
        db.exec("""
                INSERT INTO queue_items
                    (id, tenant_id, queue_name, reference, priority, status, content, retries, created_at)
                VALUES (?, ?, ?, ?, ?, 'NEW', ?, 0, now())
                """, id, tenantId, antrean, referensi, prioritas, isi);
    }

    /** Sama seperti pengambilan pekerjaan: RETURNING + SKIP LOCKED, satu langkah. */
    public Map<String, Object> ambilButir(UUID tenantId, String antrean, String robot) {
        List<Map<String, Object>> diambil = db.rows("""
                UPDATE queue_items
                   SET status = 'IN_PROGRESS', robot_name = ?, started_at = now()
                 WHERE id = (
                       SELECT id FROM queue_items
                        WHERE tenant_id = ? AND queue_name = ? AND status = 'NEW'
                        ORDER BY CASE priority
                                   WHEN 'High' THEN 0
                                   WHEN 'Normal' THEN 1
                                   ELSE 2
                                 END,
                                 created_at
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED)
             RETURNING id, reference, priority, status, content, retries, created_at, started_at
                """, robot, tenantId, antrean);

        return diambil.isEmpty() ? null : diambil.get(0);
    }

    /** Kembalikan ke antrean untuk dicoba lagi; robot_name dikosongkan supaya siapa pun boleh mengambilnya. */
    public void cobaLagi(UUID tenantId, UUID id, String galat) {
        db.exec("""
                UPDATE queue_items
                   SET status = 'NEW', retries = retries + 1, exception = ?,
                       started_at = NULL, robot_name = NULL
                 WHERE tenant_id = ? AND id = ?
                """, galat, tenantId, id);
    }

    public void selesaikanButir(UUID tenantId, UUID id, QueueItemStatus status,
                                String keluaran, String galat) {
        db.exec("""
                UPDATE queue_items
                   SET status = ?, output = ?, exception = ?, ended_at = now()
                 WHERE tenant_id = ? AND id = ?
                """, status.name(), keluaran, galat, tenantId, id);
    }

    public int hapusButir(UUID tenantId, UUID id) {
        return db.exec("DELETE FROM queue_items WHERE tenant_id = ? AND id = ?", tenantId, id);
    }
}
