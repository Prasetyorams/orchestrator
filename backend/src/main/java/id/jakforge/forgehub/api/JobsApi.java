package id.jakforge.forgehub.api;

import id.jakforge.forgehub.data.Db;
import id.jakforge.forgehub.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * Pekerjaan: menjadwalkan, mengambil, melaporkan, menghentikan.
 *
 * <p>Empat dari endpoint di sini dipanggil langsung oleh robot, dan bentuknya
 * tidak boleh berubah dari yang dikirim ForgeHub .NET: JakRunner membaca
 * {@code job.id}, Studio dan activity Start Job membaca {@code id} lalu
 * {@code state}.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobsApi {

    /** Keadaan yang boleh dilaporkan robot. Daftar IZIN, bukan larangan. */
    private static final Set<String> KEADAAN_SAH =
            Set.of("PENDING", "RUNNING", "SUCCESSFUL", "FAULTED", "STOPPING", "STOPPED");

    private static final Set<String> KEADAAN_SELESAI =
            Set.of("SUCCESSFUL", "FAULTED", "STOPPED");

    private final Db db;

    public JobsApi(Db db) {
        this.db = db;
    }

    // -----------------------------------------------------------------
    // Daftar dan detail
    // -----------------------------------------------------------------

    @GetMapping
    public List<Map<String, Object>> daftar(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String process,
            @RequestParam(required = false) Integer limit) {

        UUID tenantId = CurrentUser.get().tenantId();

        // Penyaring dirangkai, bukan dijabarkan jadi empat kueri terpisah.
        // Halaman detail proses memerlukan "jalan milik proses ini saja", dan
        // menambahkannya sebagai cabang baru berarti empat kombinasi yang harus
        // dijaga tetap sama isinya.
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        where.add("tenant_id = ?");
        args.add(tenantId);

        if (state != null && !state.isBlank()) {
            where.add("state = ?");
            args.add(state.toUpperCase(Locale.ROOT));
        }

        if (process != null && !process.isBlank()) {
            where.add("process_name = ?");
            args.add(process);
        }

        args.add(Badan.batas(limit, 100, 1000));

        return db.rows("""
                SELECT id, process_name, robot_name, machine_name, state, source, priority,
                       progress, info, created_at, started_at, ended_at
                  FROM jobs
                 WHERE %s
                 ORDER BY created_at DESC
                 LIMIT ?
                """.formatted(String.join(" AND ", where)), args.toArray());
    }

    /**
     * Ambil pekerjaan berikutnya untuk sebuah robot.
     *
     * <p>Dipetakan SEBELUM {@code /{id}} — kalau tidak, "next" akan tertangkap
     * sebagai sebuah id dan endpoint ini tidak pernah tercapai. Spring memilih
     * pola yang lebih spesifik lebih dulu, tapi mengandalkan itu diam-diam
     * membuat urutan berkas menjadi penting tanpa alasan yang terlihat.
     *
     * <p>Pengambilannya HARUS satu langkah yang tak terbagi. Versi .NET
     * melakukan UPDATE lalu SELECT terpisah yang mencari "pekerjaan RUNNING
     * terbaru milik robot ini" — dan robot yang entah bagaimana punya dua
     * pekerjaan berjalan akan menerima yang salah. Di sini UPDATE mengembalikan
     * baris yang benar-benar diubahnya lewat RETURNING, jadi tidak ada
     * kemungkinan salah tebak.
     *
     * <p>FOR UPDATE SKIP LOCKED menutup sisi satunya: dua robot yang bertanya
     * bersamaan tidak akan melihat baris yang sama sebagai PENDING, sehingga
     * satu pekerjaan tidak pernah dijalankan dua kali.
     */
    @GetMapping("/next")
    @Transactional
    public ResponseEntity<?> berikutnya(@RequestParam(required = false) String robot) {
        if (robot == null || robot.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Parameter 'robot' wajib diisi."));
        }

        UUID tenantId = CurrentUser.get().tenantId();

        List<Map<String, Object>> diambil = db.rows("""
                UPDATE jobs
                   SET state = 'RUNNING',
                       robot_name = ?,
                       started_at = now(),
                       info = 'Sedang dijalankan.'
                 WHERE id = (
                       SELECT id FROM jobs
                        WHERE tenant_id = ?
                          AND state = 'PENDING'
                          AND (robot_name IS NULL OR robot_name = '' OR robot_name = ?)
                        ORDER BY CASE priority
                                   WHEN 'High' THEN 0
                                   WHEN 'Normal' THEN 1
                                   ELSE 2
                                 END,
                                 created_at
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED)
             RETURNING id, process_name, robot_name, state, priority, input_json,
                       created_at, started_at
                """, robot, tenantId, robot);

        Map<String, Object> job = diambil.isEmpty() ? null : diambil.get(0);

        // Selalu 200 dengan {"job": null}, bukan 404. Tidak ada pekerjaan
        // BUKAN kesalahan — itu jawaban yang benar dan yang paling sering,
        // dan robot yang menerima 404 setiap beberapa detik akan memenuhi
        // catatannya dengan galat yang bukan galat.
        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("job", job);

        return ResponseEntity.ok(hasil);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> satu(@PathVariable String id) {
        UUID jobId = Db.uuid(id);
        if (jobId == null) return tidakAda();

        Map<String, Object> row = db.row("""
                SELECT id, process_name, robot_name, machine_name, state, source, priority,
                       progress, info, input_json, output_json, created_at, started_at, ended_at
                  FROM jobs
                 WHERE tenant_id = ? AND id = ?
                """, CurrentUser.get().tenantId(), jobId);

        return row == null ? tidakAda() : ResponseEntity.ok(row);
    }

    // -----------------------------------------------------------------
    // Menjadwalkan
    // -----------------------------------------------------------------

    @PostMapping
    @Transactional
    public ResponseEntity<?> buat(@RequestBody(required = false) Map<String, Object> body) {
        String process = Badan.nama(body, "processName");

        if (process == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "processName wajib diisi."));
        }

        UUID tenantId = CurrentUser.get().tenantId();

        if (!db.exists("SELECT count(*) FROM processes WHERE tenant_id = ? AND name = ?", tenantId, process)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Proses '" + process + "' belum diterbitkan ke ForgeHub."));
        }

        UUID id = Db.newId();
        String robot = Badan.teks(body, "robotName");

        db.exec("""
                INSERT INTO jobs
                    (id, tenant_id, process_name, robot_name, machine_name, state, source, priority,
                     progress, info, input_json, created_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, 0, ?, ?, now())
                """,
                id, tenantId, process, robot, Badan.teks(body, "machineName"),
                Badan.teks(body, "source", "Manual"), Badan.teks(body, "priority", "Normal"),
                "Menunggu robot yang tersedia.", Badan.teks(body, "inputJson"));

        db.exec("""
                INSERT INTO logs (tenant_id, level, message, process_name, robot_name, job_id, logged_at)
                VALUES (?, 'INFO', ?, ?, ?, ?, now())
                """,
                tenantId, "Pekerjaan dijadwalkan untuk " + process + ".", process, robot, id);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("id", id.toString());

        return ResponseEntity.ok(hasil);
    }

    // -----------------------------------------------------------------
    // Laporan dari robot
    // -----------------------------------------------------------------

    @PostMapping("/{id}/state")
    @Transactional
    public ResponseEntity<?> keadaan(@PathVariable String id,
                                     @RequestBody(required = false) Map<String, Object> body) {

        UUID jobId = Db.uuid(id);
        if (jobId == null) return tidakAda();

        String state = Badan.teks(body, "state", "").toUpperCase(Locale.ROOT);

        if (!KEADAAN_SAH.contains(state)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Keadaan tidak dikenal: '" + state + "'."));
        }

        boolean selesai = KEADAAN_SELESAI.contains(state);
        int progress = selesai ? 100 : Math.clamp(Badan.bulat(body, "progress", 0), 0, 100);
        String info = Badan.teks(body, "info");
        String output = Badan.teks(body, "outputJson");

        UUID tenantId = CurrentUser.get().tenantId();

        Object process = db.scalar(
                "SELECT process_name FROM jobs WHERE tenant_id = ? AND id = ?", tenantId, jobId);

        if (process == null) return tidakAda();

        // COALESCE: info dan outputJson yang tidak dikirim TIDAK menghapus yang
        // sudah ada. Robot melaporkan kemajuan berkali-kali dan hanya mengisi
        // sebagian medan tiap kali.
        db.exec("""
                UPDATE jobs
                   SET state = ?,
                       progress = ?,
                       info = COALESCE(?, info),
                       output_json = COALESCE(?, output_json),
                       ended_at = CASE WHEN ? THEN now() ELSE ended_at END
                 WHERE tenant_id = ? AND id = ?
                """, state, progress, info, output, selesai, tenantId, jobId);

        if ("FAULTED".equals(state)) {
            Peringatan.catat(db, tenantId, "Error", "Pekerjaan gagal",
                    process + " gagal: " + (info == null ? "tanpa keterangan" : info), "jobs");
        }

        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Permintaan berhenti.
     *
     * <p>Keadaannya menjadi STOPPING, bukan langsung STOPPED: yang benar-benar
     * bisa menghentikan proses adalah robotnya, dan ia baru tahu pada denyut
     * berikutnya. Pekerjaan yang masih PENDING belum dipegang siapa pun, jadi
     * yang itu boleh langsung STOPPED.
     */
    @PostMapping("/{id}/stop")
    public ResponseEntity<?> hentikan(@PathVariable String id) {
        UUID jobId = Db.uuid(id);
        if (jobId == null) return tidakAda();

        int berubah = db.exec("""
                UPDATE jobs
                   SET state = CASE WHEN state = 'PENDING' THEN 'STOPPED' ELSE 'STOPPING' END,
                       info = 'Diminta berhenti.',
                       ended_at = CASE WHEN state = 'PENDING' THEN now() ELSE ended_at END
                 WHERE tenant_id = ? AND id = ? AND state IN ('PENDING', 'RUNNING')
                """, CurrentUser.get().tenantId(), jobId);

        return berubah == 0
                ? ResponseEntity.badRequest().body(Map.of(
                        "error", "Pekerjaan itu tidak sedang menunggu atau berjalan."))
                : ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> hapus(@PathVariable String id) {
        UUID jobId = Db.uuid(id);
        if (jobId == null) return tidakAda();

        int terhapus = db.exec("DELETE FROM jobs WHERE tenant_id = ? AND id = ?",
                CurrentUser.get().tenantId(), jobId);

        return terhapus == 0 ? tidakAda() : ResponseEntity.ok(Map.of("ok", true));
    }

    private static ResponseEntity<?> tidakAda() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Pekerjaan tidak ada."));
    }
}
