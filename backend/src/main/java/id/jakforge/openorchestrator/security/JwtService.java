package id.jakforge.openorchestrator.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 *
 * <p>Dibuat lewat {@code CryptoConfig}, bukan dipindai sebagai komponen.
 */
public class JwtService {

    /** HS256 menuntut kunci minimal 256 bit. */
    static final int MIN_SECRET_BYTES = 32;

    static final String CLAIM_TENANT_ID = "tenantId";
    static final String CLAIM_USERNAME = "username";
    static final String CLAIM_ROLE = "role";

    private final SecretKey signingKey;
    private final long expirationMinutes;

    public JwtService(String secret, long expirationMinutes) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);

        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "openorchestrator.jwt.secret terlalu pendek: butuh minimal " + MIN_SECRET_BYTES + " karakter untuk HS256.");
        }

        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.expirationMinutes = expirationMinutes;
    }

    public String issueToken(UUID userId, UUID tenantId, String username, String role) {
        Instant issuedAt = Instant.now();

        return Jwts.builder()
                .subject(userId.toString())
                .claims(Map.of(
                        CLAIM_TENANT_ID, tenantId.toString(),
                        CLAIM_USERNAME, username,
                        CLAIM_ROLE, role))
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(Duration.ofMinutes(expirationMinutes))))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Pemanggil yang tertulis di token.
     *
     * @throws JwtException             kalau tokennya rusak, palsu, atau kedaluwarsa
     * @throws IllegalArgumentException kalau isinya tidak berbentuk token OpenOrchestrator
     */
    public OpenOrchestratorPrincipal parsePrincipal(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return new OpenOrchestratorPrincipal(
                UUID.fromString(claims.getSubject()),
                UUID.fromString(claims.get(CLAIM_TENANT_ID, String.class)),
                claims.get(CLAIM_USERNAME, String.class),
                claims.get(CLAIM_ROLE, String.class));
    }

    public long expirationMinutes() {
        return expirationMinutes;
    }
}
