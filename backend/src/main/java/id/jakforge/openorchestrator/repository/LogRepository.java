package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.model.LogLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Catatan jalannya automasi — dari robot, dan dari OpenOrchestrator sendiri.
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
     * Saringan catatan. Medan null (atau koleksi kosong) tidak membatasi apa pun;
     * yang terisi digabung dengan AND.
     *
     * @param levels       nama tingkat yang boleh tampil, persis seperti tersimpan
     * @param folderId     null berarti seluruh penyewa. Catatan tidak menyimpan
     *                     foldernya: catatan sebuah pekerjaan milik folder
     *                     pekerjaannya, dan catatan tanpa pekerjaan — yang ditulis
     *                     OpenOrchestrator sendiri — milik folder prosesnya.
     * @param from         batas bawah waktu, termasuk
     * @param to           batas atas waktu, tidak termasuk
     * @param text         potongan teks yang harus ada di pesan, tanpa membedakan huruf besar
     */
    public record Filter(Collection<String> levels, String robotName, String processName, UUID jobId, UUID folderId,
                         String machineName, String hostIdentity, Instant from, Instant to, String text) {

        /** Hanya tempat dan waktu — cakupan daftar pilihan saringan. */
        public Filter scope() {
            return new Filter(null, null, null, jobId, folderId, null, null, from, to, null);
        }
    }

    /**
     * Catatan terbaru yang cocok dengan saringan, dari yang paling baru.
     *
     * <p>Urutan argumennya dijaga: penyewa, tingkat rincian, tingkat yang
     * diminta, lalu saringan lain — saringan yang kosong tidak menambah apa pun.
     */
    public List<Map<String, Object>> search(UUID tenantId, Filter filter, int limit) {
        List<Object> args = new ArrayList<>();
        String where = where(tenantId, filter, args);

        args.add(limit);

        return database.queryRows("""
                SELECT id, level, message, robot_name, machine_name, host_identity, process_name, job_id, logged_at
                  FROM logs
                 WHERE %s
                 ORDER BY id DESC
                 LIMIT ?
                """.formatted(where), args.toArray());
    }

    /** Bentuk lama: tanpa mesin, host identity, waktu, dan teks. */
    public List<Map<String, Object>> search(UUID tenantId, Collection<String> levels, String robotName,
                                            String processName, UUID jobId, UUID folderId, int limit) {
        return search(tenantId, new Filter(levels, robotName, processName, jobId, folderId, null, null, null, null,
                null), limit);
    }

    /**
     * Pilihan saringan Mesin, Proses, dan Host Identity: nilai yang BENAR-BENAR
     * ada di catatan dalam cakupan itu (folder, pekerjaan, waktu), tanpa
     * ganda, tiap jenis paling banyak {@code perKind}.
     *
     * <p>Satu kali baca untuk ketiganya (GROUPING SETS), bukan tiga kueri.
     */
    public Map<String, List<String>> findFilterOptions(UUID tenantId, Filter scope, int perKind) {
        List<Object> args = new ArrayList<>();
        String where = where(tenantId, scope, args);

        Map<String, List<String>> options = new LinkedHashMap<>();
        options.put("machines", new ArrayList<>());
        options.put("processes", new ArrayList<>());
        options.put("hostIdentities", new ArrayList<>());

        for (Map<String, Object> row : database.queryRows("""
                SELECT machine_name, process_name, host_identity,
                       GROUPING(machine_name) AS without_machine,
                       GROUPING(process_name) AS without_process,
                       GROUPING(host_identity) AS without_host
                  FROM logs
                 WHERE %s
                 GROUP BY GROUPING SETS ((machine_name), (process_name), (host_identity))
                """.formatted(where), args.toArray())) {
            collect(options.get("machines"), row, "withoutMachine", "machineName");
            collect(options.get("processes"), row, "withoutProcess", "processName");
            collect(options.get("hostIdentities"), row, "withoutHost", "hostIdentity");
        }

        for (List<String> values : options.values()) {
            values.sort(String.CASE_INSENSITIVE_ORDER);
            if (values.size() > perKind) values.subList(perKind, values.size()).clear();
        }

        return options;
    }

    private static void collect(List<String> target, Map<String, Object> row, String groupingColumn, String column) {
        Object value = row.get(column);
        if (value != null && ((Number) row.get(groupingColumn)).intValue() == 0) target.add(value.toString());
    }

    /** Kondisi WHERE bersama untuk daftar, pilihan saringan, dan ekspor; argumennya ditambahkan ke {@code args}. */
    private static String where(UUID tenantId, Filter filter, List<Object> args) {
        List<String> conditions = new ArrayList<>();

        conditions.add("tenant_id = ?");
        args.add(tenantId);

        // Baris TRACE/DEBUG tidak lagi diterima, tapi yang tersimpan SEBELUM
        // aturan itu — termasuk hasil pindahan dari OpenOrchestrator .NET — masih ada
        // di tabel. Disaring di sini supaya ketiga tampilan log (Catatan,
        // detail proses, detail pekerjaan) sama-sama tidak menampilkannya.
        List<String> verboseLevels = LogLevel.verboseLevelNames();
        conditions.add("level NOT IN (" + Database.placeholders(verboseLevels.size()) + ")");
        args.addAll(verboseLevels);

        if (filter.levels() != null && !filter.levels().isEmpty()) {
            conditions.add("level IN (" + Database.placeholders(filter.levels().size()) + ")");
            args.addAll(filter.levels());
        }

        if (filter.robotName() != null && !filter.robotName().isBlank()) {
            conditions.add("robot_name = ?");
            args.add(filter.robotName());
        }

        if (filter.processName() != null && !filter.processName().isBlank()) {
            conditions.add("process_name = ?");
            args.add(filter.processName());
        }

        if (filter.jobId() != null) {
            conditions.add("job_id = ?");
            args.add(filter.jobId());
        }

        if (filter.folderId() != null) {
            conditions.add("""
                    (job_id IN (SELECT j.id FROM jobs j WHERE j.tenant_id = ? AND j.folder_id = ?)
                     OR (job_id IS NULL
                         AND process_name IN (SELECT p.name FROM processes p
                                               WHERE p.tenant_id = ? AND p.folder_id = ?)))""");
            args.add(tenantId);
            args.add(filter.folderId());
            args.add(tenantId);
            args.add(filter.folderId());
        }

        if (filter.machineName() != null && !filter.machineName().isBlank()) {
            conditions.add("machine_name = ?");
            args.add(filter.machineName());
        }

        if (filter.hostIdentity() != null && !filter.hostIdentity().isBlank()) {
            conditions.add("host_identity = ?");
            args.add(filter.hostIdentity());
        }

        if (filter.from() != null) {
            conditions.add("logged_at >= ?");
            args.add(OffsetDateTime.ofInstant(filter.from(), ZoneOffset.UTC));
        }

        if (filter.to() != null) {
            conditions.add("logged_at < ?");
            args.add(OffsetDateTime.ofInstant(filter.to(), ZoneOffset.UTC));
        }

        if (filter.text() != null && !filter.text().isBlank()) {
            conditions.add("message ILIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(filter.text()) + "%");
        }

        return String.join(" AND ", conditions);
    }

    /**
     * % dan _ dari teks yang dicari berarti dirinya sendiri, bukan pola: yang
     * mencari "100%" tidak boleh mendapat setiap baris yang memuat "100".
     */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Tulis satu baris.
     *
     * <p>loggedAt yang tidak dikirim diisi waktu server. Robot yang jamnya
     * meleset tetap menghasilkan catatan yang urut menurut kedatangannya.
     */
    public void insert(UUID tenantId, LogLevel level, String message, String robotName, String machineName,
                       String processName, UUID jobId, String loggedAt) {
        insert(tenantId, level, message, robotName, machineName, processName, jobId, loggedAt, null);
    }

    /**
     * @param hostIdentity akun Windows tempat robot berjalan, kalau robotnya menyebut; yang kosong
     *                     diisi pemicu basis data dari pekerjaan atau robotnya (V10), sama seperti
     *                     mesinnya
     */
    public void insert(UUID tenantId, LogLevel level, String message, String robotName, String machineName,
                       String processName, UUID jobId, String loggedAt, String hostIdentity) {
        database.update("""
                INSERT INTO logs (tenant_id, level, message, robot_name, machine_name,
                                  process_name, job_id, logged_at, host_identity)
                VALUES (?, ?, ?, ?, ?, ?, ?, COALESCE(?::timestamptz, now()), ?)
                """, tenantId, level.name(), message, robotName, machineName, processName, jobId, loggedAt,
                hostIdentity);
    }

    /**
     * Satu baris dari Robot Agent.
     *
     * <p>Baris dengan (job, seq) yang sudah ada DILEWATI tanpa galat: kiriman
     * ulang dari antrean agent sesudah jaringan pulih adalah hal biasa, bukan
     * kesalahan.
     *
     * @return 1 kalau tertulis, 0 kalau kiriman ulang
     */
    public int insertAgentLine(UUID tenantId, LogLevel level, String message, String robotName, String machineName,
                               String processName, UUID jobId, String loggedAt, Long seq, String source,
                               Integer sessionId) {
        return database.update("""
                INSERT INTO logs (tenant_id, level, message, robot_name, machine_name, process_name, job_id,
                                  logged_at, seq, source, session_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, COALESCE(?::timestamptz, now()), ?, ?, ?)
                ON CONFLICT (job_id, seq) WHERE job_id IS NOT NULL AND seq IS NOT NULL DO NOTHING
                """, tenantId, level.name(), message, robotName, machineName, processName, jobId, loggedAt, seq,
                source, sessionId);
    }

    /** Baris dari Orchestrator sendiri untuk sebuah job, dengan tingkat tertentu. */
    public void insertJobEntry(UUID tenantId, LogLevel level, String message, String robotName, String processName,
                               UUID jobId) {
        database.update("""
                INSERT INTO logs (tenant_id, level, message, robot_name, process_name, job_id, logged_at, source)
                VALUES (?, ?, ?, ?, ?, ?, now(), 'Orchestrator')
                """, tenantId, level.name(), message, robotName, processName, jobId);
    }

    /** Baris tanpa robot: dipakai saat OpenOrchestrator sendiri yang mencatat. */
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
