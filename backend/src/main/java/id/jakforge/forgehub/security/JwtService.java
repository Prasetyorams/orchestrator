package id.jakforge.forgehub.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Membuat dan memeriksa token JWT.
 *
 * Rahasianya dibaca dari konfigurasi dan HARUS diganti di produksi.
 * Panjang minimalnya diperiksa saat aplikasi mulai: HS256 menuntut kunci
 * minimal 256 bit, dan kunci yang terlalu pendek gagal saat token pertama
 * dibuat — jauh setelah aplikasi dianggap sehat.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(
            @Value("${forgehub.jwt.secret}") String secret,
            @Value("${forgehub.jwt.expiration-minutes}") long expirationMinutes) {

        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "forgehub.jwt.secret terlalu pendek: butuh minimal 32 karakter untuk HS256.");
        }

        this.key = Keys.hmacShaKeyFor(bytes);
        this.expirationMinutes = expirationMinutes;
    }

    public String issue(UUID userId, UUID tenantId, String username, String role) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(userId.toString())
                .claims(Map.of(
                        "tenantId", tenantId.toString(),
                        "username", username,
                        "role", role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationMinutes * 60)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationMinutes() {
        return expirationMinutes;
    }
}
