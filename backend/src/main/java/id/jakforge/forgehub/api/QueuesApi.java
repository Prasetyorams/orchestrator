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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Antrean transaksi.
 *
 * <p>Ini yang dipakai pola ReFramework: satu proses mengisi antrean, robot-robot
 * lain mengambil butirnya satu per satu dan melaporkan hasilnya. Tiga endpoint
 * di sini dipanggil langsung oleh activity di Studio — Add Queue Item,
 * Get Queue Item, dan Set Transaction Status.
 */
@RestController
@RequestMapping("/api/queues")
public class QueuesApi {

    private static final Set<String> HASIL_SAH =
            Set.of("SUCCESSFUL", "FAILED", "RETRIED", "ABANDONED");

    private final Db db;

    public QueuesApi(Db db) {
        this.db = db;
    }

    // -----------------------------------------------------------------
    // Antrean
    // -----------------------------------------------------------------

    /** Daftar antrean lengkap dengan hitungan tiap keadaan — angka inilah yang dilihat orang lebih dulu. */
    @GetMapping
    public List<Map<String, Object>> daftar() {
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
                """, CurrentUser.get().tenantId());
    }

    @PostMapping
    public ResponseEntity<?> buat(@RequestBody(required = false) Map<String, Object> body) {
        String name = Badan.nama(body, "name");

        if (name == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nama antrean wajib diisi."));
        }

        UUID tenantId = CurrentUser.get().tenantId();

        if (db.exists("SELECT count(*) FROM queues WHERE tenant_id = ? AND name = ?", tenantId, name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Antrean '" + name + "' sudah ada."));
        }

        db.exec("""
                INSERT INTO queues (id, tenant_id, name, description, max_retries, accept_duplicates, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, Db.newId(), tenantId, name, Badan.teks(body, "description"),
                Badan.bulat(body, "maxRetries", 3), Badan.benar(body, "acceptDuplicates", false));

        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{name}")
    @Transactional
    public ResponseEntity<?> hapus(@PathVariable String name) {
        UUID tenantId = CurrentUser.get().tenantId();

        // Isinya ikut dihapus. Butir yang menggantung tanpa antrean induk tidak
        // akan pernah bisa dilihat lagi lewat jalan mana pun, dan tetap terhitung
        // dalam angka apa pun yang menjumlahkan seluruh tabel.
        db.exec("DELETE FROM queue_items WHERE tenant_id = ? AND queue_name = ?", tenantId, name);

        int terhapus = db.exec("DELETE FROM queues WHERE tenant_id = ? AND name = ?", tenantId, name);

        return terhapus == 0
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Antrean tidak ada."))
                : ResponseEntity.ok(Map.of("ok", true));
    }

    // -----------------------------------------------------------------
    // Butir
    // -----------------------------------------------------------------

    @GetMapping("/{name}/items")
    public List<Map<String, Object>> butir(@PathVariable String name,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) Integer limit) {

        UUID tenantId = CurrentUser.get().tenantId();
        int batas = Badan.batas(limit, 200, 2000);

        // Satu kueri untuk kedua kemungkinan: penyaring status yang null
        // dilewati oleh "? IS NULL". Dua kueri yang hampir sama adalah dua
        // tempat yang harus diubah bersamaan setiap kali kolomnya bertambah.
        String s = status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT);

        return db.rows("""
                SELECT id, reference, priority, status, content, output, exception, retries,
                       robot_name, created_at, started_at, ended_at
                  FROM queue_items
                 WHERE tenant_id = ? AND queue_name = ?
                   AND (?::text IS NULL OR status = ?::text)
                 ORDER BY created_at DESC
                 LIMIT ?
                """, tenantId, name, s, s, batas);
    }

    /** Menambah butir. Dipakai activity "Add Queue Item" di Studio. */
    @PostMapping("/{name}/items")
    @Transactional
    public ResponseEntity<?> tambahButir(@PathVariable String name,
                                         @RequestBody(required = false) Map<String, Object> body) {

        UUID tenantId = CurrentUser.get().tenantId();

        Map<String, Object> antrean = db.row(
                "SELECT accept_duplicates FROM queues WHERE tenant_id = ? AND name = ?", tenantId, name);

        if (antrean == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Antrean '" + name + "' tidak ada."));
        }

        String referensi = Badan.teks(body, "reference");
        boolean bolehKembar = Boolean.TRUE.equals(antrean.get("acceptDuplicates"));

        // Penolakan kembar hanya berlaku untuk butir yang BELUM selesai.
        // Referensi yang sama boleh muncul lagi besok; yang tidak boleh adalah
        // dua salinan menunggu diproses pada saat yang sama.
        if (!bolehKembar && referensi != null && !referensi.isEmpty()) {
            boolean kembar = db.exists("""
                    SELECT count(*) FROM queue_items
                     WHERE tenant_id = ? AND queue_name = ? AND reference = ?
                       AND status IN ('NEW', 'IN_PROGRESS')
                    """, tenantId, name, referensi);

            if (kembar) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "Butir dengan referensi '" + referensi + "' sudah menunggu di antrean ini."));
            }
        }

        UUID id = Db.newId();

        db.exec("""
                INSERT INTO queue_items
                    (id, tenant_id, queue_name, reference, priority, status, content, retries, created_at)
                VALUES (?, ?, ?, ?, ?, 'NEW', ?, 0, now())
                """, id, tenantId, name, referensi,
                Badan.teks(body, "priority", "Normal"), Badan.teks(body, "content"));

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("id", id.toString());

        return ResponseEntity.ok(hasil);
    }

    /**
     * Robot mengambil butir berikutnya.
     *
     * <p>Sama seperti pengambilan pekerjaan: RETURNING memastikan yang
     * dikembalikan benar-benar baris yang diubah, dan SKIP LOCKED memastikan
     * dua robot tidak pernah memegang butir yang sama. Versi .NET mencarinya
     * kembali dengan {@code ORDER BY started_at DESC LIMIT 1}, yang memberi
     * jawaban salah begitu satu robot memegang dua butir sekaligus.
     */
    @PostMapping("/{name}/next")
    @Transactional
    public ResponseEntity<?> ambilButir(@PathVariable String name,
                                        @RequestBody(required = false) Map<String, Object> body) {

        String robot = Badan.teks(body, "robotName");
        UUID tenantId = CurrentUser.get().tenantId();

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
                """, robot, tenantId, name);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("item", diambil.isEmpty() ? null : diambil.get(0));

        return ResponseEntity.ok(hasil);
    }

    /** Hasil pemrosesan satu butir. Dipakai activity "Set Transaction Status". */
    @PostMapping("/items/{id}/result")
    @Transactional
    public ResponseEntity<?> hasilButir(@PathVariable String id,
                                        @RequestBody(required = false) Map<String, Object> body) {

        UUID itemId = Db.uuid(id);
        if (itemId == null) return butirTidakAda();

        String status = Badan.teks(body, "status", "").toUpperCase(Locale.ROOT);

        if (!HASIL_SAH.contains(status)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Status hasil tidak dikenal: '" + status + "'."));
        }

        UUID tenantId = CurrentUser.get().tenantId();

        Map<String, Object> butir = db.row("""
                SELECT queue_name, retries, reference FROM queue_items
                 WHERE tenant_id = ? AND id = ?
                """, tenantId, itemId);

        if (butir == null) return butirTidakAda();

        String antrean = (String) butir.get("queueName");
        long percobaan = ((Number) butir.get("retries")).longValue();

        if ("FAILED".equals(status)) {
            long maksimum = db.count(
                    "SELECT max_retries FROM queues WHERE tenant_id = ? AND name = ?", tenantId, antrean);

            // Butir gagal dicoba lagi selama jatah percobaannya belum habis: ia
            // dikembalikan ke NEW dengan hitungan percobaan bertambah, dan
            // robot_name dikosongkan supaya robot mana pun boleh mengambilnya.
            if (percobaan < maksimum) {
                db.exec("""
                        UPDATE queue_items
                           SET status = 'NEW', retries = retries + 1, exception = ?,
                               started_at = NULL, robot_name = NULL
                         WHERE tenant_id = ? AND id = ?
                        """, Badan.teks(body, "exception"), tenantId, itemId);

                Map<String, Object> hasil = new LinkedHashMap<>();
                hasil.put("ok", true);
                hasil.put("retried", true);
                hasil.put("attempt", percobaan + 1);

                return ResponseEntity.ok(hasil);
            }

            Object referensi = butir.get("reference");

            Peringatan.catat(db, tenantId, "Warning", "Butir antrean gagal permanen",
                    "Butir '" + (referensi == null ? id : referensi) + "' di antrean " + antrean +
                    " gagal setelah " + percobaan + " percobaan ulang.", "queues");
        }

        db.exec("""
                UPDATE queue_items
                   SET status = ?, output = ?, exception = ?, ended_at = now()
                 WHERE tenant_id = ? AND id = ?
                """, status, Badan.teks(body, "output"), Badan.teks(body, "exception"), tenantId, itemId);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("retried", false);

        return ResponseEntity.ok(hasil);
    }

    @DeleteMapping("/items/{id}")
    public ResponseEntity<?> hapusButir(@PathVariable String id) {
        UUID itemId = Db.uuid(id);
        if (itemId == null) return butirTidakAda();

        int terhapus = db.exec("DELETE FROM queue_items WHERE tenant_id = ? AND id = ?",
                CurrentUser.get().tenantId(), itemId);

        return terhapus == 0 ? butirTidakAda() : ResponseEntity.ok(Map.of("ok", true));
    }

    private static ResponseEntity<?> butirTidakAda() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Butir antrean tidak ada."));
    }
}
