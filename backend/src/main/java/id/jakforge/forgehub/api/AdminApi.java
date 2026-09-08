package id.jakforge.forgehub.api;

import id.jakforge.forgehub.data.Db;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.JwtService;
import id.jakforge.forgehub.security.Passwords;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Map;
import java.util.UUID;

/**
 * Pengguna, peran, penyewa, lisensi, dan setelan.
 *
 * <p>Semuanya hanya-baca kecuali pengguna, dan perubahan pengguna dibatasi ke
 * Administrator. Pembatasannya diperiksa di sini, bukan diserahkan ke
 * antarmuka: tombol yang disembunyikan tetap bisa dilewati oleh siapa pun yang
 * memanggil API-nya langsung.
 */
@RestController
@RequestMapping("/api")
public class AdminApi {

    private static final int PUTUS_SETELAH_DETIK = 45;

    private final Db db;
    private final JwtService jwt;
    private final String zonaTampilan;

    public AdminApi(Db db, JwtService jwt,
                    @Value("${forgehub.display-timezone:UTC}") String zonaTampilan) {
        this.db = db;
        this.jwt = jwt;
        this.zonaTampilan = zonaTampilan;
    }

    // -----------------------------------------------------------------
    // Penyewa
    // -----------------------------------------------------------------

    @GetMapping("/tenants")
    public List<Map<String, Object>> penyewa() {
        return db.rows("""
                SELECT t.id, t.name, t.display_name, t.created_at,
                       (SELECT count(*) FROM users u WHERE u.tenant_id = t.id)  AS user_count,
                       (SELECT count(*) FROM robots r WHERE r.tenant_id = t.id) AS robot_count
                  FROM tenants t
                 ORDER BY t.name
                """);
    }

    // -----------------------------------------------------------------
    // Pengguna
    // -----------------------------------------------------------------

    /**
     * Ringkasan pengguna.
     *
     * <p>Kolom {@code password_hash} TIDAK ikut dipilih, bahkan dalam bentuk
     * teracaknya: ringkasan yang bocor masih bisa ditebak di luar sini tanpa
     * batas percobaan dan tanpa ada yang tahu.
     */
    @GetMapping("/users")
    public List<Map<String, Object>> pengguna() {
        return db.rows("""
                SELECT id, username, display_name, email, role, is_active, created_at, last_login_at
                  FROM users
                 WHERE tenant_id = ?
                 ORDER BY username
                """, CurrentUser.get().tenantId());
    }

    @PostMapping("/users")
    @Transactional
    public ResponseEntity<?> simpanPengguna(@RequestBody(required = false) Map<String, Object> body) {
        ForgeHubPrincipal p = CurrentUser.get();

        if (!administrator(p)) return terlarang("Hanya Administrator yang boleh mengubah pengguna.");

        String username = Badan.nama(body, "username");

        if (username == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nama pengguna wajib diisi."));
        }

        String password = Badan.teks(body, "password");
        UUID tenantId = p.tenantId();

        Object adaId = db.scalar("SELECT id FROM users WHERE tenant_id = ? AND username = ?",
                tenantId, username);

        if (adaId != null) {
            db.exec("""
                    UPDATE users
                       SET display_name = COALESCE(?, display_name),
                           email = COALESCE(?, email),
                           role = COALESCE(?, role),
                           is_active = ?
                     WHERE tenant_id = ? AND username = ?
                    """, Badan.teks(body, "displayName"), Badan.teks(body, "email"),
                    Badan.teks(body, "role"), Badan.benar(body, "isActive", true),
                    tenantId, username);

            // Kata sandi hanya diganti kalau memang dikirim. Tanpa syarat ini,
            // menyunting alamat surel akan diam-diam mengosongkan sandinya —
            // dan yang bersangkutan baru tahu saat gagal masuk besok pagi.
            if (password != null && !password.isEmpty()) {
                if (password.length() < 6) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Kata sandi minimal 6 karakter."));
                }

                db.exec("UPDATE users SET password_hash = ? WHERE tenant_id = ? AND username = ?",
                        Passwords.hash(password), tenantId, username);
            }

            Map<String, Object> hasil = new LinkedHashMap<>();
            hasil.put("ok", true);
            hasil.put("created", false);

            return ResponseEntity.ok(hasil);
        }

        if (password == null || password.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Pengguna baru butuh kata sandi minimal 6 karakter."));
        }

        db.exec("""
                INSERT INTO users (id, tenant_id, username, password_hash, display_name,
                                   email, role, is_active, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                """, Db.newId(), tenantId, username, Passwords.hash(password),
                Badan.teks(body, "displayName", username), Badan.teks(body, "email"),
                Badan.teks(body, "role", "Automation User"), Badan.benar(body, "isActive", true));

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("created", true);

        return ResponseEntity.ok(hasil);
    }

    @DeleteMapping("/users/{username}")
    @Transactional
    public ResponseEntity<?> hapusPengguna(@PathVariable String username) {
        ForgeHubPrincipal p = CurrentUser.get();

        if (!administrator(p)) return terlarang("Hanya Administrator yang boleh menghapus pengguna.");

        // Menghapus diri sendiri akan mengunci orangnya keluar dari ForgeHub
        // miliknya sendiri, dan tidak ada jalan masuk lain untuk membatalkannya.
        if (username.equalsIgnoreCase(p.username())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Tidak bisa menghapus akun yang sedang dipakai."));
        }

        UUID tenantId = p.tenantId();

        // Penyewa tanpa satu pun administrator aktif tidak bisa diurus lagi:
        // tidak ada yang bisa membuat administrator baru, dan tidak ada pintu
        // belakang untuk memperbaikinya.
        boolean iniAdmin = db.exists("""
                SELECT count(*) FROM users
                 WHERE tenant_id = ? AND username = ? AND role = 'Administrator'
                """, tenantId, username);

        long jumlahAdmin = db.count("""
                SELECT count(*) FROM users
                 WHERE tenant_id = ? AND role = 'Administrator' AND is_active
                """, tenantId);

        if (iniAdmin && jumlahAdmin <= 1) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Ini satu-satunya Administrator yang tersisa."));
        }

        int terhapus = db.exec("DELETE FROM users WHERE tenant_id = ? AND username = ?",
                tenantId, username);

        return terhapus == 0
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Pengguna tidak ada."))
                : ResponseEntity.ok(Map.of("ok", true));
    }

    // -----------------------------------------------------------------
    // Peran dan lisensi
    // -----------------------------------------------------------------

    @GetMapping("/roles")
    public List<Map<String, Object>> peran() {
        return db.rows("""
                SELECT r.id, r.name, r.description, r.permissions, r.created_at,
                       (SELECT count(*) FROM users u
                         WHERE u.tenant_id = r.tenant_id AND u.role = r.name) AS user_count
                  FROM roles r
                 WHERE r.tenant_id = ?
                 ORDER BY r.name
                """, CurrentUser.get().tenantId());
    }

    /**
     * Lisensi.
     *
     * <p>"Terpakai" dihitung dari robot yang benar-benar ada, bukan dari angka
     * yang pernah dituliskan seseorang ke kolom {@code used}. Angka yang
     * disimpan akan menyimpang begitu satu robot dihapus tanpa lewat layar ini.
     */
    @GetMapping("/licensing")
    public List<Map<String, Object>> lisensi() {
        UUID tenantId = CurrentUser.get().tenantId();

        List<Map<String, Object>> baris = db.rows("""
                SELECT id, product, total, used, expires_at
                  FROM licenses WHERE tenant_id = ? ORDER BY product
                """, tenantId);

        long attended = db.count(
                "SELECT count(*) FROM robots WHERE tenant_id = ? AND type = 'Attended'", tenantId);

        long unattended = db.count(
                "SELECT count(*) FROM robots WHERE tenant_id = ? AND type <> 'Attended'", tenantId);

        for (Map<String, Object> b : baris) {
            String produk = String.valueOf(b.get("product"));

            b.put("used", produk.contains("Attended") && !produk.contains("Unattended")
                    ? attended : unattended);
        }

        return baris;
    }

    // -----------------------------------------------------------------
    // Setelan
    // -----------------------------------------------------------------

    @GetMapping("/settings")
    public Map<String, Object> setelan() {
        ForgeHubPrincipal p = CurrentUser.get();

        Map<String, Object> jumlah = db.row("""
                SELECT (SELECT count(*) FROM users     WHERE tenant_id = ?) AS users,
                       (SELECT count(*) FROM robots    WHERE tenant_id = ?) AS robots,
                       (SELECT count(*) FROM processes WHERE tenant_id = ?) AS processes,
                       (SELECT count(*) FROM jobs      WHERE tenant_id = ?) AS jobs,
                       (SELECT count(*) FROM logs      WHERE tenant_id = ?) AS logs
                """, p.tenantId(), p.tenantId(), p.tenantId(), p.tenantId(), p.tenantId());

        Object namaPenyewa = db.scalar("SELECT name FROM tenants WHERE id = ?", p.tenantId());

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("tenant", namaPenyewa);
        hasil.put("serverTime", Db.nowText());
        hasil.put("displayTimezone", zonaTampilan);
        hasil.put("robotOfflineAfterSeconds", PUTUS_SETELAH_DETIK);
        hasil.put("tokenLifetimeHours", jwt.getExpirationMinutes() / 60);

        // "database", bukan "dataDirectory": di sini datanya ada di PostgreSQL,
        // bukan di sebuah folder. Menyisakan nama medan yang lama akan membuat
        // layar Settings menampilkan jalur folder yang tidak berarti apa-apa.
        hasil.put("database", "PostgreSQL");
        hasil.put("counts", jumlah);

        return hasil;
    }

    // -----------------------------------------------------------------

    private static boolean administrator(ForgeHubPrincipal p) {
        return "Administrator".equals(p.role());
    }

    private static ResponseEntity<?> terlarang(String pesan) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", pesan));
    }
}
