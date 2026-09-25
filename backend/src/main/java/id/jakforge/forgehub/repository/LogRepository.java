package id.jakforge.forgehub.repository;

import id.jakforge.forgehub.model.LogLevel;
import id.jakforge.forgehub.model.Severity;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Catatan dan peringatan.
 *
 * <p>Dua tabel, satu repository, karena keduanya adalah jejak kejadian dan
 * hampir setiap kesalahan menulis ke keduanya sekaligus: log untuk yang
 * mencarinya, peringatan untuk yang tidak.
 */
@Repository
public class LogRepository {

    private final Db db;

    public LogRepository(Db db) {
        this.db = db;
    }

    // -----------------------------------------------------------------
    // Catatan
    // -----------------------------------------------------------------

    /**
     * @param tingkat nama tingkat yang boleh tampil, persis seperti tersimpan.
     *                Kosong berarti semua tingkat — selain rincian, yang
     *                SELALU disaring.
     */
    public List<Map<String, Object>> cari(UUID tenantId, Collection<String> tingkat, String robot,
                                          String process, UUID jobId, int batas) {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        where.add("tenant_id = ?");
        args.add(tenantId);

        // Baris TRACE/DEBUG tidak lagi diterima, tapi yang tersimpan SEBELUM
        // aturan itu — termasuk hasil pindahan dari ForgeHub .NET — masih ada
        // di tabel. Disaring di sini supaya ketiga tampilan log (Catatan,
        // detail proses, detail pekerjaan) sama-sama tidak menampilkannya.
        List<String> rincian = LogLevel.namaRincian();
        where.add("level NOT IN (" + tandaTanya(rincian.size()) + ")");
        args.addAll(rincian);

        if (tingkat != null && !tingkat.isEmpty()) {
            where.add("level IN (" + tandaTanya(tingkat.size()) + ")");
            args.addAll(tingkat);
        }

        if (robot != null && !robot.isBlank()) {
            where.add("robot_name = ?");
            args.add(robot);
        }

        if (process != null && !process.isBlank()) {
            where.add("process_name = ?");
            args.add(process);
        }

        if (jobId != null) {
            where.add("job_id = ?");
            args.add(jobId);
        }

        args.add(batas);

        return db.rows("""
                SELECT id, level, message, robot_name, machine_name, process_name, job_id, logged_at
                  FROM logs
                 WHERE %s
                 ORDER BY id DESC
                 LIMIT ?
                """.formatted(String.join(" AND ", where)), args.toArray());
    }

    /**
     * Tulis satu baris.
     *
     * <p>loggedAt yang tidak dikirim diisi waktu server. Robot yang jamnya
     * meleset tetap menghasilkan catatan yang urut menurut kedatangannya.
     */
    public void tulis(UUID tenantId, LogLevel level, String pesan, String robot, String mesin,
                      String proses, UUID jobId, String waktu) {
        db.exec("""
                INSERT INTO logs (tenant_id, level, message, robot_name, machine_name,
                                  process_name, job_id, logged_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, COALESCE(?::timestamptz, now()))
                """, tenantId, level.name(), pesan, robot, mesin, proses, jobId, waktu);
    }

    /** Baris tanpa robot: dipakai saat ForgeHub sendiri yang mencatat. */
    public void tulisSistem(UUID tenantId, String pesan, String proses, UUID jobId) {
        db.exec("""
                INSERT INTO logs (tenant_id, level, message, process_name, job_id, logged_at)
                VALUES (?, 'INFO', ?, ?, ?, now())
                """, tenantId, pesan, proses, jobId);
    }

    public int buangLebihTuaDari(UUID tenantId, int hari) {
        return db.exec("""
                DELETE FROM logs WHERE tenant_id = ? AND logged_at < now() - make_interval(days => ?)
                """, tenantId, hari);
    }


    // -----------------------------------------------------------------
    // Peringatan
    // -----------------------------------------------------------------

    public List<Map<String, Object>> peringatan(UUID tenantId, boolean hanyaBelumDibaca, int batas) {
        return peringatan(tenantId, hanyaBelumDibaca, List.of(), batas);
    }

    /** @param tingkat nilai severity yang boleh tampil; kosong berarti semua. */
    public List<Map<String, Object>> peringatan(UUID tenantId, boolean hanyaBelumDibaca,
                                                Collection<String> tingkat, int batas) {
        List<Object> args = new ArrayList<>(List.of(tenantId, hanyaBelumDibaca));
        String saringTingkat = "";

        if (!tingkat.isEmpty()) {
            saringTingkat = " AND severity IN (" + tandaTanya(tingkat.size()) + ")";
            args.addAll(tingkat);
        }

        args.add(batas);

        return db.rows("""
                SELECT id, severity, title, message, source, is_read, created_at
                  FROM alerts
                 WHERE tenant_id = ? AND (NOT ? OR NOT is_read)%s
                 ORDER BY id DESC
                 LIMIT ?
                """.formatted(saringTingkat), args.toArray());
    }

    public void catatPeringatan(UUID tenantId, Severity tingkat, String judul,
                                String pesan, String sumber) {
        db.exec("""
                INSERT INTO alerts (tenant_id, severity, title, message, source, is_read, created_at)
                VALUES (?, ?, ?, ?, ?, FALSE, now())
                """, tenantId, tingkat.nilai(), judul, pesan, sumber);
    }

    public int tandaiDibaca(UUID tenantId, long id) {
        return db.exec("UPDATE alerts SET is_read = TRUE WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    public int tandaiSemuaDibaca(UUID tenantId) {
        return db.exec("UPDATE alerts SET is_read = TRUE WHERE tenant_id = ? AND NOT is_read", tenantId);
    }

    public long belumDibaca(UUID tenantId) {
        return db.count("SELECT count(*) FROM alerts WHERE tenant_id = ? AND NOT is_read", tenantId);
    }

    /** "?, ?, ?" — nilainya tetap dikirim sebagai parameter, tidak pernah ditempel ke SQL. */
    private static String tandaTanya(int jumlah) {
        return String.join(", ", Collections.nCopies(jumlah, "?"));
    }
}
