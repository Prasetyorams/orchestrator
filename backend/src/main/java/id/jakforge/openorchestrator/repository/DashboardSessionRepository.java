package id.jakforge.openorchestrator.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sesi dasbor (V18): satu baris per masuk lewat dasbor, diperiksa di setiap
 * permintaan bertokennya. Tidak ada token atau sandi yang disimpan di sini.
 *
 * <p>Waktu selalu dikirim dari aplikasi (Clock), bukan {@code now()} basis data:
 * pemeriksaan dan pencatatan memakai jam yang sama, dan bisa diuji.
 */
@Repository
@RequiredArgsConstructor
public class DashboardSessionRepository {

    private final Database database;

    public record SessionRow(UUID id, UUID tenantId, UUID userId, String username, Instant createdAt,
                             Instant lastActivityAt, Instant expiresAt, Instant revokedAt, String revokeReason) {
    }

    private static final String COLUMNS =
            "id, tenant_id, user_id, username, created_at, last_activity_at, expires_at, revoked_at, revoke_reason";

    public void insert(UUID id, UUID tenantId, UUID userId, String username, Instant now, Instant expiresAt,
                       String userAgent) {
        database.update("""
                INSERT INTO dashboard_sessions
                    (id, tenant_id, user_id, username, created_at, last_activity_at, expires_at, user_agent)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, userId, username, at(now), at(now), at(expiresAt), userAgent);
    }

    public Optional<SessionRow> find(UUID tenantId, UUID id) {
        List<SessionRow> rows = database.query("SELECT " + COLUMNS + " FROM dashboard_sessions WHERE tenant_id = ? AND id = ?",
                DashboardSessionRepository::row, tenantId, id);
        return rows.stream().findFirst();
    }

    /**
     * Catat aktivitas — HANYA kalau sesinya masih berlaku saat ini juga:
     * belum dicabut, belum habis umurnya, dan (kalau ada batas menganggur)
     * aktivitas sebelumnya belum lewat batas. Satu UPDATE, jadi tidak ada celah
     * antara memeriksa dan mencatat: sesi yang sudah lewat batas, atau yang
     * baru saja dikeluarkan, tidak bisa dihidupkan lagi oleh laporan yang
     * datang bersamaan.
     *
     * @param idleCutoff aktivitas terakhir harus SESUDAH saat ini; null = tanpa batas menganggur
     */
    public boolean recordActivity(UUID tenantId, UUID id, Instant now, Instant idleCutoff) {
        if (idleCutoff == null) {
            return database.update("""
                    UPDATE dashboard_sessions SET last_activity_at = ?
                     WHERE tenant_id = ? AND id = ? AND revoked_at IS NULL AND expires_at > ?
                    """, at(now), tenantId, id, at(now)) == 1;
        }

        return database.update("""
                UPDATE dashboard_sessions SET last_activity_at = ?
                 WHERE tenant_id = ? AND id = ? AND revoked_at IS NULL AND expires_at > ? AND last_activity_at > ?
                """, at(now), tenantId, id, at(now), at(idleCutoff)) == 1;
    }

    /** @return true hanya untuk pencabutan PERTAMA — yang kedua tidak mengubah apa pun */
    public boolean revoke(UUID tenantId, UUID id, Instant now, String reason) {
        return database.update("""
                UPDATE dashboard_sessions SET revoked_at = ?, revoke_reason = ?
                 WHERE tenant_id = ? AND id = ? AND revoked_at IS NULL
                """, at(now), reason, tenantId, id) == 1;
    }

    /** Semua sesi dasbor seorang pengguna, kecuali {@code exceptId} (boleh null). */
    public int revokeAllOfUser(UUID tenantId, String username, Instant now, String reason, UUID exceptId) {
        if (exceptId == null) {
            return database.update("""
                    UPDATE dashboard_sessions SET revoked_at = ?, revoke_reason = ?
                     WHERE tenant_id = ? AND username = ? AND revoked_at IS NULL
                    """, at(now), reason, tenantId, username);
        }

        return database.update("""
                UPDATE dashboard_sessions SET revoked_at = ?, revoke_reason = ?
                 WHERE tenant_id = ? AND username = ? AND revoked_at IS NULL AND id <> ?
                """, at(now), reason, tenantId, username, exceptId);
    }

    /** Yang sudah berakhir lebih dari 30 hari tidak perlu disimpan lagi. */
    public void deleteStale(Instant now) {
        database.update("DELETE FROM dashboard_sessions WHERE COALESCE(revoked_at, expires_at) < ?",
                at(now.minus(Duration.ofDays(30))));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static SessionRow row(ResultSet rs, int rowNumber) throws SQLException {
        return new SessionRow(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("username"), instant(rs, "created_at"),
                instant(rs, "last_activity_at"), instant(rs, "expires_at"), instant(rs, "revoked_at"),
                rs.getString("revoke_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
