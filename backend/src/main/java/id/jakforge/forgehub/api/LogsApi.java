package id.jakforge.forgehub.api;

import id.jakforge.forgehub.data.Db;
import id.jakforge.forgehub.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Catatan dari robot.
 *
 * <p>Dikirim BERKELOMPOK, bukan satu per satu. Robot menghasilkan puluhan
 * baris per detik saat berjalan, dan satu permintaan HTTP per baris berarti
 * robotnya menghabiskan lebih banyak waktu menunggu jaringan daripada
 * mengerjakan pekerjaannya.
 */
@RestController
@RequestMapping("/api/logs")
public class LogsApi {

    /**
     * Batas baris per kiriman.
     *
     * <p>Ada supaya satu kiriman tidak bisa menahan transaksi terlalu lama dan
     * menghalangi dasbor membaca. Robot yang punya lebih banyak akan
     * mengirimkannya sebagai kelompok berikutnya.
     */
    private static final int MAKS_BARIS = 1000;

    private static final Set<String> TINGKAT_SAH =
            Set.of("TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR", "FATAL");

    private final Db db;

    public LogsApi(Db db) {
        this.db = db;
    }

    @GetMapping
    public List<Map<String, Object>> daftar(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String robot,
            @RequestParam(required = false) String process,
            @RequestParam(name = "jobId", required = false) String jobId,
            @RequestParam(required = false) Integer limit) {

        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        where.add("tenant_id = ?");
        args.add(CurrentUser.get().tenantId());

        if (level != null && !level.isBlank()) {
            where.add("level = ?");
            args.add(level.toUpperCase(Locale.ROOT));
        }

        if (robot != null && !robot.isBlank()) {
            where.add("robot_name = ?");
            args.add(robot);
        }

        if (process != null && !process.isBlank()) {
            where.add("process_name = ?");
            args.add(process);
        }

        if (jobId != null && !jobId.isBlank()) {
            UUID id = Db.uuid(jobId);

            // Id yang bukan UUID tidak akan pernah cocok dengan apa pun. Yang
            // dikembalikan daftar kosong, bukan 500 karena kegagalan penguraian
            // di dalam basis data.
            if (id == null) return Db.kosong();

            where.add("job_id = ?");
            args.add(id);
        }

        args.add(Badan.batas(limit, 200, 2000));

        return db.rows("""
                SELECT id, level, message, robot_name, machine_name, process_name, job_id, logged_at
                  FROM logs
                 WHERE %s
                 ORDER BY id DESC
                 LIMIT ?
                """.formatted(String.join(" AND ", where)), args.toArray());
    }

    @PostMapping
    @Transactional
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> tulis(@RequestBody(required = false) Map<String, Object> body) {
        Object mentah = body == null ? null : body.get("lines");

        if (!(mentah instanceof List<?> lines)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Butuh { \"lines\": [ ... ] }."));
        }

        if (lines.size() > MAKS_BARIS) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Terlalu banyak baris dalam satu kiriman. Batasnya " + MAKS_BARIS + "."));
        }

        UUID tenantId = CurrentUser.get().tenantId();
        int ditulis = 0;

        for (Object o : lines) {
            if (!(o instanceof Map)) continue;

            Map<String, Object> baris = (Map<String, Object>) o;

            String pesan = Badan.teks(baris, "message");
            if (pesan == null || pesan.isBlank()) continue;

            // Tingkat yang tidak dikenal menjadi INFO, bukan ditolak. Satu
            // salah ketik pada satu baris tidak boleh membuang seluruh kiriman
            // — yang hilang kemudian justru catatan di sekitar kegagalan yang
            // sedang dicari orang.
            String tingkat = Badan.teks(baris, "level", "INFO").toUpperCase(Locale.ROOT);
            if (!TINGKAT_SAH.contains(tingkat)) tingkat = "INFO";

            db.exec("""
                    INSERT INTO logs (tenant_id, level, message, robot_name, machine_name,
                                      process_name, job_id, logged_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, COALESCE(?::timestamptz, now()))
                    """,
                    tenantId, tingkat, pesan,
                    Badan.teks(baris, "robotName"), Badan.teks(baris, "machineName"),
                    Badan.teks(baris, "processName"), Db.uuid(Badan.teks(baris, "jobId")),
                    Badan.teks(baris, "loggedAt"));

            ditulis++;

            // Kesalahan dari robot juga menjadi peringatan. Log dibaca kalau ada
            // yang sengaja mencarinya; peringatan muncul sendiri.
            if ("ERROR".equals(tingkat) || "FATAL".equals(tingkat)) {
                String robot = Badan.teks(baris, "robotName", "robot");
                Peringatan.catat(db, tenantId, "Error", "Kesalahan pada " + robot, pesan, "logs");
            }
        }

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("written", ditulis);

        return ResponseEntity.ok(hasil);
    }

    /**
     * Buang catatan lama.
     *
     * <p>Batas hari WAJIB dan tidak boleh nol: "hapus semua log" adalah
     * perintah yang tidak bisa dibatalkan, dan bentuk yang paling mudah
     * dijalankan tanpa sengaja.
     */
    @DeleteMapping
    public ResponseEntity<?> bersihkan(@RequestParam(name = "olderThanDays", required = false) Integer hari) {
        if (hari == null || hari < 1) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Parameter 'olderThanDays' wajib diisi dan minimal 1."));
        }

        int terhapus = db.exec(
                "DELETE FROM logs WHERE tenant_id = ? AND logged_at < now() - make_interval(days => ?)",
                CurrentUser.get().tenantId(), hari);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("deleted", terhapus);

        return ResponseEntity.ok(hasil);
    }
}
