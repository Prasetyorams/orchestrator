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
import java.util.LinkedHashMap;
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
 * <p>Tiga jenis token, dibedakan klaim {@code kind}: pengguna (tanpa klaim itu,
 * bentuk lama), agent, dan executor. Token lama tanpa {@code kind} tetap
 * dibaca sebagai token pengguna.
 *
 * <p>Dibuat lewat {@code CryptoConfig}, bukan dipindai sebagai komponen.
 */
public class JwtService {

    /** HS256 menuntut kunci minimal 256 bit. */
    static final int MIN_SECRET_BYTES = 32;

    static final String CLAIM_TENANT_ID = "tenantId";
    static final String CLAIM_USERNAME = "username";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_KIND = "kind";
    static final String CLAIM_JOB_ID = "jobId";
    static final String CLAIM_FOLDER_ID = "folderId";
    static final String CLAIM_KEY_ID = "keyId";

    static final String KIND_AGENT = "agent";
    static final String KIND_EXECUTOR = "executor";

    /** Token yang baru dibuat beserta saat kedaluwarsanya. */
    public record IssuedToken(String token, Instant expiresAt) {
    }

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
        return sign(userId, Map.of(
                CLAIM_TENANT_ID, tenantId.toString(),
                CLAIM_USERNAME, username,
                CLAIM_ROLE, role), Duration.ofMinutes(expirationMinutes)).token();
    }

    /** Token Robot Agent: hanya berlaku di /api/agent. */
    public IssuedToken issueAgentToken(UUID machineId, UUID tenantId, String machineName, String keyId,
                                       Duration ttl) {
        return sign(machineId, Map.of(
                CLAIM_KIND, KIND_AGENT,
                CLAIM_TENANT_ID, tenantId.toString(),
                CLAIM_USERNAME, machineName,
                CLAIM_KEY_ID, keyId), ttl);
    }

    /** Token Executor untuk satu job; dipakai activities atas nama robotnya. */
    public IssuedToken issueExecutorToken(UUID robotId, UUID tenantId, String robotName, UUID jobId, UUID folderId,
                                          Duration ttl) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(CLAIM_KIND, KIND_EXECUTOR);
        claims.put(CLAIM_TENANT_ID, tenantId.toString());
        claims.put(CLAIM_USERNAME, robotName);
        claims.put(CLAIM_JOB_ID, jobId.toString());
        if (folderId != null) claims.put(CLAIM_FOLDER_ID, folderId.toString());

        return sign(robotId, claims, ttl);
    }

    private IssuedToken sign(UUID subject, Map<String, ?> claims, Duration ttl) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(ttl);

        String token = Jwts.builder()
                .subject(subject.toString())
                .claims(claims)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();

        return new IssuedToken(token, expiresAt);
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

        UUID subject = UUID.fromString(claims.getSubject());
        UUID tenantId = UUID.fromString(claims.get(CLAIM_TENANT_ID, String.class));
        String name = claims.get(CLAIM_USERNAME, String.class);
        String kind = claims.get(CLAIM_KIND, String.class);

        if (KIND_AGENT.equals(kind)) {
            return OpenOrchestratorPrincipal.agent(subject, tenantId, name, claims.get(CLAIM_KEY_ID, String.class));
        }

        if (KIND_EXECUTOR.equals(kind)) {
            String folderId = claims.get(CLAIM_FOLDER_ID, String.class);

            return OpenOrchestratorPrincipal.executor(subject, tenantId, name,
                    UUID.fromString(claims.get(CLAIM_JOB_ID, String.class)),
                    folderId == null ? null : UUID.fromString(folderId));
        }

        if (kind != null) throw new IllegalArgumentException("Jenis token tidak dikenal: " + kind);

        return new OpenOrchestratorPrincipal(subject, tenantId, name, claims.get(CLAIM_ROLE, String.class));
    }

    public long expirationMinutes() {
        return expirationMinutes;
    }
}
