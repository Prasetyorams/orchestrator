package id.jakforge.forgehub.repository;

import id.jakforge.forgehub.model.LogLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Catatan jalannya automasi — dari robot, dan dari ForgeHub sendiri.
 *
 * <p>Peringatan ada di {@link AlertRepository}: hampir setiap kesalahan menulis
 * ke keduanya sekaligus — catatan untuk yang mencarinya, peringatan untuk yang
 * tidak.
 */
@Repository
@RequiredArgsConstructor
public class LogRepository {

    private final Database database;

    /**
     * @param levels   nama tingkat yang boleh tampil, persis seperti tersimpan.
     *                 Kosong berarti semua tingkat — selain rincian, yang
     *                 SELALU disaring.
     * @param folderId null berarti seluruh penyewa. Catatan tidak menyimpan
     *                 foldernya: catatan sebuah pekerjaan milik folder
     *                 pekerjaannya, dan catatan tanpa pekerjaan — yang ditulis
     *                 ForgeHub sendiri — milik folder prosesnya.
     */
    public List<Map<String, Object>> search(UUID tenantId, Collection<String> levels, String robotName,
                                            String processName, UUID jobId, UUID folderId, int limit) {
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        conditions.add("tenant_id = ?");
        args.add(tenantId);

        // Baris TRACE/DEBUG tidak lagi diterima, tapi yang tersimpan SEBELUM
        // aturan itu — termasuk hasil pindahan dari ForgeHub .NET — masih ada
        // di tabel. Disaring di sini supaya ketiga tampilan log (Catatan,
        // detail proses, detail pekerjaan) sama-sama tidak menampilkannya.
        List<String> verboseLevels = LogLevel.verboseLevelNames();
        conditions.add("level NOT IN (" + Database.placeholders(verboseLevels.size()) + ")");
        args.addAll(verboseLevels);

        if (levels != null && !levels.isEmpty()) {
            conditions.add("level IN (" + Database.placeholders(levels.size()) + ")");
            args.addAll(levels);
        }

        if (robotName != null && !robotName.isBlank()) {
            conditions.add("robot_name = ?");
            args.add(robotName);
        }

        if (processName != null && !processName.isBlank()) {
            conditions.add("process_name = ?");
            args.add(processName);
        }

        if (jobId != null) {
            conditions.add("job_id = ?");
            args.add(jobId);
        }

        if (folderId != null) {
            conditions.add("""
                    (job_id IN (SELECT j.id FROM jobs j WHERE j.tenant_id = ? AND j.folder_id = ?)
                     OR (job_id IS NULL
                         AND process_name IN (SELECT p.name FROM processes p
                                               WHERE p.tenant_id = ? AND p.folder_id = ?)))""");
            args.add(tenantId);
            args.add(folderId);
            args.add(tenantId);
            args.add(folderId);
        }

        args.add(limit);

        return database.queryRows("""
                SELECT id, level, message, robot_name, machine_name, process_name, job_id, logged_at
                  FROM logs
                 WHERE %s
                 ORDER BY id DESC
                 LIMIT ?
                """.formatted(String.join(" AND ", conditions)), args.toArray());
    }

    /**
     * Tulis satu baris.
     *
     * <p>loggedAt yang tidak dikirim diisi waktu server. Robot yang jamnya
     * meleset tetap menghasilkan catatan yang urut menurut kedatangannya.
     */
    public void insert(UUID tenantId, LogLevel level, String message, String robotName, String machineName,
                       String processName, UUID jobId, String loggedAt) {
        database.update("""
                INSERT INTO logs (tenant_id, level, message, robot_name, machine_name,
                                  process_name, job_id, logged_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, COALESCE(?::timestamptz, now()))
                """, tenantId, level.name(), message, robotName, machineName, processName, jobId, loggedAt);
    }

    /** Baris tanpa robot: dipakai saat ForgeHub sendiri yang mencatat. */
    public void insertSystemEntry(UUID tenantId, String message, String processName, UUID jobId) {
        database.update("""
                INSERT INTO logs (tenant_id, level, message, process_name, job_id, logged_at)
                VALUES (?, 'INFO', ?, ?, ?, now())
                """, tenantId, message, processName, jobId);
    }

    public int deleteOlderThan(UUID tenantId, int days) {
        return database.update("""
                DELETE FROM logs WHERE tenant_id = ? AND logged_at < now() - make_interval(days => ?)
                """, tenantId, days);
    }
}
