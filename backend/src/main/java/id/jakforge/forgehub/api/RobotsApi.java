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
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Robot, mesin, dan lingkungan.
 *
 * <p>Endpoint denyut dipanggil JakRunner setiap beberapa detik dan merupakan
 * satu-satunya cara ForgeHub tahu sebuah robot masih hidup.
 */
@RestController
@RequestMapping("/api")
public class RobotsApi {

    /**
     * Setelah berapa lama tanpa denyut sebuah robot dianggap putus.
     *
     * <p>45 detik: cukup longgar untuk melewati satu denyut yang hilang karena
     * jaringan tersendat, cukup ketat supaya robot yang mati tidak terlihat
     * tersedia selama satu menit penuh dan menerima pekerjaan yang tidak akan
     * pernah dijalankannya.
     */
    private static final int PUTUS_SETELAH_DETIK = 45;

    /**
     * Status dihitung saat DIBACA, bukan disimpan.
     *
     * <p>Robot yang mati tidak sempat memberi tahu bahwa ia mati — itulah
     * artinya mati. Kolom status yang hanya diperbarui oleh robotnya sendiri
     * akan selamanya berbunyi AVAILABLE untuk mesin yang sudah dimatikan
     * seminggu lalu. Jadi yang tersimpan adalah waktu denyut terakhir, dan
     * kesimpulannya diambil di sini.
     */
    private static final String STATUS = """
            CASE
                WHEN last_heartbeat_at IS NULL THEN 'DISCONNECTED'
                WHEN now() - last_heartbeat_at > interval '%d seconds' THEN 'DISCONNECTED'
                ELSE status
            END AS status
            """.formatted(PUTUS_SETELAH_DETIK);

    private final Db db;

    public RobotsApi(Db db) {
        this.db = db;
    }

    // -----------------------------------------------------------------
    // Robot
    // -----------------------------------------------------------------

    @GetMapping("/robots")
    public List<Map<String, Object>> daftar() {
        return db.rows("""
                SELECT id, name, machine_name, username, type, environment, description,
                       %s, cpu_percent, memory_mb, last_heartbeat_at, created_at
                  FROM robots
                 WHERE tenant_id = ?
                 ORDER BY name
                """.formatted(STATUS), CurrentUser.get().tenantId());
    }

    @GetMapping("/robots/{name}")
    public ResponseEntity<?> satu(@PathVariable String name) {
        Map<String, Object> row = db.row("""
                SELECT id, name, machine_name, username, type, environment, description,
                       %s, cpu_percent, memory_mb, last_heartbeat_at, created_at
                  FROM robots
                 WHERE tenant_id = ? AND name = ?
                """.formatted(STATUS), CurrentUser.get().tenantId(), name);

        return row == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Robot '" + name + "' tidak ada."))
                : ResponseEntity.ok(row);
    }

    /**
     * Denyut dari robot.
     *
     * <p>Robot yang belum dikenal MENDAFTARKAN DIRINYA di sini, bukan ditolak.
     * Memasang JakRunner di mesin baru lalu harus membuka dasbor untuk
     * mendaftarkannya lebih dulu adalah langkah yang selalu terlupakan, dan
     * gejalanya — robot menyala tapi tidak muncul di mana pun — tidak
     * mengarahkan siapa pun ke langkah yang terlupa itu.
     */
    @PostMapping("/robots/{name}/heartbeat")
    @Transactional
    public ResponseEntity<?> denyut(@PathVariable String name,
                                    @RequestBody(required = false) Map<String, Object> body) {

        String status = Badan.teks(body, "status", "AVAILABLE").toUpperCase(Locale.ROOT);
        double cpu = Badan.angka(body, "cpuPercent", 0);
        double memori = Badan.angka(body, "memoryMb", 0);
        String mesin = Badan.teks(body, "machineName");

        UUID tenantId = CurrentUser.get().tenantId();

        boolean ada = db.exists(
                "SELECT count(*) FROM robots WHERE tenant_id = ? AND name = ?", tenantId, name);

        if (!ada) {
            db.exec("""
                    INSERT INTO robots
                        (id, tenant_id, name, machine_name, type, environment, description,
                         status, cpu_percent, memory_mb, last_heartbeat_at, created_at)
                    VALUES (?, ?, ?, ?, 'Attended', 'Production',
                            'Terdaftar sendiri saat denyut pertama.', ?, ?, ?, now(), now())
                    """, Db.newId(), tenantId, name, mesin, status, cpu, memori);

            pastikanMesin(tenantId, mesin);

            Peringatan.catat(db, tenantId, "Info", "Robot baru terdaftar",
                    "Robot '" + name + "' menyambung untuk pertama kali.", "robots");
        } else {
            // COALESCE pada machine_name: denyut yang tidak menyebut nama mesin
            // tidak boleh MENGHAPUS nama yang sudah diketahui.
            db.exec("""
                    UPDATE robots
                       SET status = ?, cpu_percent = ?, memory_mb = ?,
                           last_heartbeat_at = now(),
                           machine_name = COALESCE(?, machine_name)
                     WHERE tenant_id = ? AND name = ?
                    """, status, cpu, memori, mesin, tenantId, name);
        }

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("serverTime", Db.nowText());

        return ResponseEntity.ok(hasil);
    }

    @PostMapping("/robots")
    @Transactional
    public ResponseEntity<?> buat(@RequestBody(required = false) Map<String, Object> body) {
        String name = Badan.nama(body, "name");

        if (name == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nama robot wajib diisi."));
        }

        UUID tenantId = CurrentUser.get().tenantId();

        if (db.exists("SELECT count(*) FROM robots WHERE tenant_id = ? AND name = ?", tenantId, name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Robot '" + name + "' sudah ada."));
        }

        String mesin = Badan.teks(body, "machineName");

        db.exec("""
                INSERT INTO robots
                    (id, tenant_id, name, machine_name, username, type, environment, description,
                     status, cpu_percent, memory_mb, last_heartbeat_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DISCONNECTED', 0, 0, NULL, now())
                """, Db.newId(), tenantId, name, mesin, Badan.teks(body, "username"),
                Badan.teks(body, "type", "Unattended"),
                Badan.teks(body, "environment", "Production"),
                Badan.teks(body, "description"));

        pastikanMesin(tenantId, mesin);

        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/robots/{name}")
    public ResponseEntity<?> hapus(@PathVariable String name) {
        int terhapus = db.exec("DELETE FROM robots WHERE tenant_id = ? AND name = ?",
                CurrentUser.get().tenantId(), name);

        return terhapus == 0
                ? ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Robot '" + name + "' tidak ada."))
                : ResponseEntity.ok(Map.of("ok", true));
    }

    // -----------------------------------------------------------------
    // Mesin dan lingkungan
    // -----------------------------------------------------------------

    @GetMapping("/machines")
    public List<Map<String, Object>> mesin() {
        return db.rows("""
                SELECT m.id, m.name, m.type, m.license_key, m.description, m.created_at,
                       (SELECT count(*) FROM robots r
                         WHERE r.tenant_id = m.tenant_id AND r.machine_name = m.name) AS robot_count
                  FROM machines m
                 WHERE m.tenant_id = ?
                 ORDER BY m.name
                """, CurrentUser.get().tenantId());
    }

    @PostMapping("/machines")
    public ResponseEntity<?> buatMesin(@RequestBody(required = false) Map<String, Object> body) {
        String name = Badan.nama(body, "name");

        if (name == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nama mesin wajib diisi."));
        }

        UUID tenantId = CurrentUser.get().tenantId();

        if (db.exists("SELECT count(*) FROM machines WHERE tenant_id = ? AND name = ?", tenantId, name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Mesin '" + name + "' sudah ada."));
        }

        db.exec("""
                INSERT INTO machines (id, tenant_id, name, type, license_key, description, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, Db.newId(), tenantId, name, Badan.teks(body, "type", "Standard"),
                Badan.teks(body, "licenseKey"), Badan.teks(body, "description"));

        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/machines/{name}")
    public ResponseEntity<?> hapusMesin(@PathVariable String name) {
        int terhapus = db.exec("DELETE FROM machines WHERE tenant_id = ? AND name = ?",
                CurrentUser.get().tenantId(), name);

        return terhapus == 0
                ? ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Mesin '" + name + "' tidak ada."))
                : ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/environments")
    public List<Map<String, Object>> lingkungan() {
        return db.rows("""
                SELECT e.id, e.name, e.description, e.created_at,
                       (SELECT count(*) FROM robots r
                         WHERE r.tenant_id = e.tenant_id AND r.environment = e.name) AS robot_count
                  FROM environments e
                 WHERE e.tenant_id = ?
                 ORDER BY e.name
                """, CurrentUser.get().tenantId());
    }

    @PostMapping("/environments")
    public ResponseEntity<?> buatLingkungan(@RequestBody(required = false) Map<String, Object> body) {
        String name = Badan.nama(body, "name");

        if (name == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nama lingkungan wajib diisi."));
        }

        UUID tenantId = CurrentUser.get().tenantId();

        if (db.exists("SELECT count(*) FROM environments WHERE tenant_id = ? AND name = ?", tenantId, name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Lingkungan '" + name + "' sudah ada."));
        }

        db.exec("""
                INSERT INTO environments (id, tenant_id, name, description, created_at)
                VALUES (?, ?, ?, ?, now())
                """, Db.newId(), tenantId, name, Badan.teks(body, "description"));

        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/environments/{name}")
    public ResponseEntity<?> hapusLingkungan(@PathVariable String name) {
        int terhapus = db.exec("DELETE FROM environments WHERE tenant_id = ? AND name = ?",
                CurrentUser.get().tenantId(), name);

        return terhapus == 0
                ? ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Lingkungan '" + name + "' tidak ada."))
                : ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Mesin yang disebut robot didaftarkan kalau belum ada.
     *
     * <p>Tanpa ini, halaman Machines kosong sementara halaman Robots penuh —
     * dan keduanya benar menurut datanya masing-masing, yang justru membuat
     * kejanggalannya sulit dijelaskan.
     */
    private void pastikanMesin(UUID tenantId, String mesin) {
        if (mesin == null || mesin.isBlank()) return;

        if (db.exists("SELECT count(*) FROM machines WHERE tenant_id = ? AND name = ?", tenantId, mesin)) {
            return;
        }

        db.exec("""
                INSERT INTO machines (id, tenant_id, name, type, description, created_at)
                VALUES (?, ?, ?, 'Standard', 'Terdaftar sendiri lewat denyut robot.', now())
                """, Db.newId(), tenantId, mesin);
    }
}
