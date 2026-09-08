package id.jakforge.forgehub.api;

import id.jakforge.forgehub.data.Db;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.JwtService;
import id.jakforge.forgehub.security.Passwords;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Masuk, siapa saya, dan ganti kata sandi.
 *
 * <p>Bentuk jawabannya mengikuti ForgeHub .NET persis. Yang benar-benar dibaca
 * klien cuma satu medan — {@code token} — tapi sisanya dipakai dasbor, dan
 * medan yang hilang di sana muncul sebagai "undefined" di layar, bukan sebagai
 * kesalahan yang kelihatan.
 */
@RestController
@RequestMapping("/api")
public class AuthApi {

    private final Db db;
    private final JwtService jwt;

    public AuthApi(Db db, JwtService jwt) {
        this.db = db;
        this.jwt = jwt;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("product", "ForgeHub");
        hasil.put("status", "OK");
        hasil.put("time", Db.nowText());
        return hasil;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody(required = false) Map<String, Object> body) {
        String username = Badan.teks(body, "username");
        String password = Badan.teks(body, "password");

        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nama pengguna wajib diisi."));
        }

        Map<String, Object> user = db.row("""
                SELECT u.id, u.username, u.password_hash, u.display_name, u.role, u.is_active,
                       t.id AS tenant_id, t.name AS tenant_name
                  FROM users u
                  JOIN tenants t ON t.id = u.tenant_id
                 WHERE u.username = ?
                """, username);

        // Jawaban yang SAMA untuk pengguna tak dikenal dan kata sandi salah.
        // Jawaban yang berbeda memberi tahu penebak nama mana yang benar-benar
        // ada, dan itu memisahkan satu tebakan menjadi dua yang jauh lebih murah.
        ResponseEntity<?> ditolak = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Nama pengguna atau kata sandi salah."));

        if (user == null) return ditolak;
        if (!Boolean.TRUE.equals(user.get("isActive"))) return ditolak;
        if (!Passwords.verify(password, (String) user.get("passwordHash"))) return ditolak;

        UUID userId = Db.uuid((String) user.get("id"));
        UUID tenantId = Db.uuid((String) user.get("tenantId"));
        String role = (String) user.get("role");

        db.exec("UPDATE users SET last_login_at = now() WHERE id = ?", userId);

        // Kata sandi yang tersimpan dengan putaran lebih sedikit di-hash ulang
        // SEKARANG, saat kata sandi polosnya ada di tangan. Ini satu-satunya
        // saat itu mungkin; sesudah ini yang tersimpan hanya hash-nya.
        String tersimpan = (String) user.get("passwordHash");
        if (Passwords.needsRehash(tersimpan)) {
            db.exec("UPDATE users SET password_hash = ? WHERE id = ?", Passwords.hash(password), userId);
        }

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("token", jwt.issue(userId, tenantId, username, role));
        hasil.put("expiresInMinutes", jwt.getExpirationMinutes());
        hasil.put("userId", userId.toString());
        hasil.put("username", user.get("username"));
        hasil.put("displayName", user.get("displayName"));
        hasil.put("role", role);
        hasil.put("tenantId", tenantId.toString());
        hasil.put("tenantName", user.get("tenantName"));

        return ResponseEntity.ok(hasil);
    }

    @GetMapping("/auth/me")
    public ResponseEntity<?> me() {
        ForgeHubPrincipal p = CurrentUser.get();

        Map<String, Object> user = db.row("""
                SELECT u.id, u.username, u.display_name, u.email, u.role, u.is_active,
                       u.created_at, u.last_login_at, t.name AS tenant_name
                  FROM users u
                  JOIN tenants t ON t.id = u.tenant_id
                 WHERE u.id = ? AND u.tenant_id = ?
                """, p.userId(), p.tenantId());

        if (user == null) {
            // Token sah untuk pengguna yang sudah tidak ada. Itu bukan 500:
            // yang salah adalah tokennya, bukan servernya.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Pengguna sudah tidak ada."));
        }

        return ResponseEntity.ok(user);
    }

    @PostMapping("/auth/password")
    public ResponseEntity<?> gantiSandi(@RequestBody(required = false) Map<String, Object> body) {
        ForgeHubPrincipal p = CurrentUser.get();

        String lama = Badan.teks(body, "currentPassword");
        String baru = Badan.teks(body, "newPassword");

        if (baru == null || baru.length() < 8) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Kata sandi baru minimal 8 karakter."));
        }

        Object tersimpan = db.scalar("SELECT password_hash FROM users WHERE id = ?", p.userId());

        // Kata sandi lama tetap diminta walau penggunanya sudah membawa token
        // yang sah. Token bisa berasal dari layar yang ditinggal terbuka;
        // kata sandi lama hanya diketahui pemiliknya.
        if (!Passwords.verify(lama, (String) tersimpan)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Kata sandi saat ini salah."));
        }

        db.exec("UPDATE users SET password_hash = ? WHERE id = ?", Passwords.hash(baru), p.userId());

        return ResponseEntity.ok(Map.of("status", "OK"));
    }
}
