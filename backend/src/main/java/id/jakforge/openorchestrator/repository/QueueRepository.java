package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.model.QueueItemStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Akses data antrean transaksi dan butir-butirnya. */
@Repository
@RequiredArgsConstructor
public class QueueRepository {

    /** Setelan sebuah antrean yang menentukan cara butirnya diterima dan dicoba ulang. */
    public record QueueSettings(boolean acceptDuplicates, int maxRetries) {
    }

    /**
     * Yang perlu diketahui tentang satu butir saat hasilnya dilaporkan.
     *
     * @param jobId job yang memegangnya (V15); null kalau diambil pengguna atau robot v1
     */
    public record QueueItemSummary(String queueName, long retries, String reference, String status, UUID jobId) {
    }

    /** Butir yang masih dipegang job yang baru berakhir, dengan jatah percobaan antreannya. */
    public record HeldItem(UUID id, String queueName, String reference, long retries, int maxRetries) {
    }

    /** Butir yang ditinggalkan penyapu. */
    public record AbandonedItem(UUID tenantId, String queueName, String reference) {
    }

    private final Database database;

    // -----------------------------------------------------------------
    // Antrean
    // -----------------------------------------------------------------

    /**
     * Daftar antrean dengan hitungan tiap keadaan.
     *
     * <p>LEFT JOIN + FILTER, bukan lima subkueri: satu pemindaian tabel butir
     * alih-alih lima, dan seluruh angka dalam satu baris berasal dari satu saat
     * yang sama.
     *
     * @param folderId null berarti seluruh penyewa
     */
    public List<Map<String, Object>> findAll(UUID tenantId, UUID folderId) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String folderFilter = "";

        if (folderId != null) {
            folderFilter = " AND q.folder_id = ?";
            args.add(folderId);
        }

        return database.queryRows("""
                SELECT q.id, q.name, q.description, q.max_retries, q.accept_duplicates, q.created_at,
                       q.folder_id,
                       count(*) FILTER (WHERE i.status = 'NEW')         AS new_count,
                       count(*) FILTER (WHERE i.status = 'IN_PROGRESS') AS in_progress_count,
                       count(*) FILTER (WHERE i.status = 'SUCCESSFUL')  AS successful_count,
                       count(*) FILTER (WHERE i.status = 'FAILED')      AS failed_count,
                       count(i.id)                                      AS total_count
                  FROM queues q
                  LEFT JOIN queue_items i
                         ON i.tenant_id = q.tenant_id AND i.queue_name = q.name
                 WHERE q.tenant_id = ?%s
                 GROUP BY q.id, q.name, q.description, q.max_retries, q.accept_duplicates, q.created_at,
                          q.folder_id
                 ORDER BY q.name
                """.formatted(folderFilter), args.toArray());
    }

    /** Ringkasan untuk dasbor: tanpa kolom setelan, dan dibatasi. */
    public List<Map<String, Object>> findSummaries(UUID tenantId, UUID folderId, int limit) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String folderFilter = "";

        if (folderId != null) {
            folderFilter = " AND q.folder_id = ?";
            args.add(folderId);
        }

        args.add(limit);

        return database.queryRows("""
                SELECT q.name,
                       count(*) FILTER (WHERE i.status = 'NEW')         AS new_count,
                       count(*) FILTER (WHERE i.status = 'IN_PROGRESS') AS in_progress_count,
                       count(*) FILTER (WHERE i.status = 'SUCCESSFUL')  AS successful_count,
                       count(*) FILTER (WHERE i.status = 'FAILED')      AS failed_count
                  FROM queues q
                  LEFT JOIN queue_items i
                         ON i.tenant_id = q.tenant_id AND i.queue_name = q.name
                 WHERE q.tenant_id = ?%s
                 GROUP BY q.name
                 ORDER BY q.name
                 LIMIT ?
                """.formatted(folderFilter), args.toArray());
    }

    public Optional<QueueSettings> findSettings(UUID tenantId, String queueName) {
        return database.query("""
                SELECT accept_duplicates, max_retries FROM queues WHERE tenant_id = ? AND name = ?
                """, (rs, rowNumber) -> new QueueSettings(rs.getBoolean(1), rs.getInt(2)), tenantId, queueName)
                .stream().findFirst();
    }

    public boolean existsByName(UUID tenantId, String name) {
        return database.exists("SELECT count(*) FROM queues WHERE tenant_id = ? AND name = ?", tenantId, name);
    }

    /** @param folderId null berarti folder bawaan (diisi pemicu basis data). */
    public void insert(UUID tenantId, String name, String description, int maxRetries, boolean acceptDuplicates,
                       UUID folderId) {
        database.update("""
                INSERT INTO queues (id, tenant_id, name, description, max_retries, accept_duplicates,
                                    folder_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), tenantId, name, description, maxRetries, acceptDuplicates, folderId);
    }

    public int moveToFolder(UUID tenantId, String name, UUID folderId) {
        return database.update("UPDATE queues SET folder_id = ? WHERE tenant_id = ? AND name = ?",
                folderId, tenantId, name);
    }

    public int deleteByName(UUID tenantId, String name) {
        return database.update("DELETE FROM queues WHERE tenant_id = ? AND name = ?", tenantId, name);
    }

    public void deleteItemsOfQueue(UUID tenantId, String queueName) {
        database.update("DELETE FROM queue_items WHERE tenant_id = ? AND queue_name = ?", tenantId, queueName);
    }

    /**
     * Hitungan butir tiap keadaan. Butir tidak menyimpan foldernya; foldernya
     * adalah folder antreannya.
     */
    public Map<String, Object> countItemsByStatus(UUID tenantId, UUID folderId) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String folderFilter = "";

        if (folderId != null) {
            folderFilter = " AND i.queue_name IN (SELECT q.name FROM queues q"
                    + " WHERE q.tenant_id = i.tenant_id AND q.folder_id = ?)";
            args.add(folderId);
        }

        return database.queryRow("""
                SELECT count(*) FILTER (WHERE i.status = 'NEW')         AS new_items,
                       count(*) FILTER (WHERE i.status = 'IN_PROGRESS') AS in_progress,
                       count(*) FILTER (WHERE i.status = 'FAILED')      AS failed
                  FROM queue_items i WHERE i.tenant_id = ?%s
                """.formatted(folderFilter), args.toArray()).orElseGet(LinkedHashMap::new);
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
    public List<Map<String, Object>> findItems(UUID tenantId, String queueName, String status, int limit) {
        return database.queryRows("""
                SELECT id, reference, priority, status, content, output, exception, retries,
                       robot_name, created_at, started_at, ended_at
                  FROM queue_items
                 WHERE tenant_id = ? AND queue_name = ?
                   AND (?::text IS NULL OR status = ?::text)
                 ORDER BY created_at DESC
                 LIMIT ?
                """, tenantId, queueName, status, status, limit);
    }

    public Optional<QueueItemSummary> findItemSummary(UUID tenantId, UUID itemId) {
        return database.query("""
                SELECT queue_name, retries, reference, status, job_id FROM queue_items
                 WHERE tenant_id = ? AND id = ?
                   FOR UPDATE
                """, (rs, rowNumber) -> new QueueItemSummary(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, UUID.class)),
                tenantId, itemId).stream().findFirst();
    }

    public boolean hasPendingDuplicate(UUID tenantId, String queueName, String reference) {
        return database.exists("""
                SELECT count(*) FROM queue_items
                 WHERE tenant_id = ? AND queue_name = ? AND reference = ?
                   AND status IN ('NEW', 'IN_PROGRESS')
                """, tenantId, queueName, reference);
    }

    public void insertItem(UUID itemId, UUID tenantId, String queueName, String reference,
                           String priority, String content) {
        database.update("""
                INSERT INTO queue_items
                    (id, tenant_id, queue_name, reference, priority, status, content, retries, created_at)
                VALUES (?, ?, ?, ?, ?, 'NEW', ?, 0, now())
                """, itemId, tenantId, queueName, reference, priority, content);
    }

    /**
     * Sama seperti pengambilan pekerjaan: RETURNING + SKIP LOCKED, satu langkah.
     *
     * @param jobId job Robot Agent yang mengambilnya (token Executor); null untuk pengguna dan robot v1
     */
    public Optional<Map<String, Object>> claimNextItem(UUID tenantId, String queueName, String robotName, UUID jobId) {
        return database.queryRows("""
                UPDATE queue_items
                   SET status = 'IN_PROGRESS', robot_name = ?, started_at = now(), job_id = ?
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
                """, robotName, jobId, tenantId, queueName).stream().findFirst();
    }

    /**
     * Kembalikan ke antrean untuk dicoba lagi; robot_name dan job_id
     * dikosongkan supaya siapa pun boleh mengambilnya — dan supaya laporan
     * terlambat dari job lama tidak dianggap milik pengambil berikutnya.
     */
    public void requeueForRetry(UUID tenantId, UUID itemId, String exception) {
        database.update("""
                UPDATE queue_items
                   SET status = 'NEW', retries = retries + 1, exception = ?,
                       started_at = NULL, robot_name = NULL, job_id = NULL
                 WHERE tenant_id = ? AND id = ?
                """, exception, tenantId, itemId);
    }

    /** Butir yang masih IN_PROGRESS atas nama job ini, dikunci untuk dilepas. */
    public List<HeldItem> findHeldByJob(UUID tenantId, UUID jobId) {
        return database.query("""
                SELECT i.id, i.queue_name, i.reference, i.retries, coalesce(q.max_retries, 0)
                  FROM queue_items i
                  LEFT JOIN queues q ON q.tenant_id = i.tenant_id AND q.name = i.queue_name
                 WHERE i.tenant_id = ? AND i.job_id = ? AND i.status = 'IN_PROGRESS'
                 ORDER BY i.started_at
                   FOR UPDATE OF i
                """, (rs, rowNumber) -> new HeldItem(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getInt(5)),
                tenantId, jobId);
    }

    /**
     * Penyapu: butir yang IN_PROGRESS lebih lama dari {@code olderThanSeconds}
     * menjadi ABANDONED — seperti UiPath sesudah 24 jam. Untuk semua penyewa
     * sekaligus; pemanggil mengelompokkan hasilnya untuk peringatan.
     */
    public List<AbandonedItem> abandonStale(long olderThanSeconds, String exception) {
        return database.query("""
                UPDATE queue_items
                   SET status = 'ABANDONED', exception = ?, ended_at = now()
                 WHERE status = 'IN_PROGRESS'
                   AND started_at < now() - (? * interval '1 second')
             RETURNING tenant_id, queue_name, reference
                """, (rs, rowNumber) -> new AbandonedItem(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
                exception, olderThanSeconds);
    }

    public void completeItem(UUID tenantId, UUID itemId, QueueItemStatus status, String output, String exception) {
        database.update("""
                UPDATE queue_items
                   SET status = ?, output = ?, exception = ?, ended_at = now()
                 WHERE tenant_id = ? AND id = ?
                """, status.name(), output, exception, tenantId, itemId);
    }

    public int deleteItem(UUID tenantId, UUID itemId) {
        return database.update("DELETE FROM queue_items WHERE tenant_id = ? AND id = ?", tenantId, itemId);
    }
}
