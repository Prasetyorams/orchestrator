package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.common.Uuids;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Kode sekali pakai dan sambungan Open Assistant yang masuk lewat dasbor (V11).
 *
 * <p>Kode dan refresh token hanya dicari lewat HASH-nya; nilai aslinya tidak
 * pernah sampai ke basis data.
 */
@Repository
@RequiredArgsConstructor
public class AssistantSessionRepository {

    private final Database database;

    // -----------------------------------------------------------------
    // Kode
    // -----------------------------------------------------------------

    public void insertCode(UUID id, UUID tenantId, UUID userId, String codeHash, String client, String redirectUri,
                           String codeChallenge, String machineName, OffsetDateTime expiresAt) {
        database.update("""
                INSERT INTO assistant_codes
                    (id, tenant_id, user_id, code_hash, client, redirect_uri, code_challenge, machine_name, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, userId, codeHash, client, redirectUri, codeChallenge, machineName, expiresAt);
    }

    /**
     * Kode itu, DIKUNCI sampai transaksinya selesai: dua penukaran serentak
     * dengan kode yang sama tidak boleh sama-sama lolos.
     */
    public Optional<Map<String, Object>> lockCode(String codeHash) {
        return database.queryRow("""
                SELECT id, tenant_id, user_id, client, redirect_uri, code_challenge, machine_name,
                       expires_at > now() AS live, used_at IS NOT NULL AS used
                  FROM assistant_codes
                 WHERE code_hash = ?
                   FOR UPDATE
                """, codeHash);
    }

    public void markCodeUsed(UUID codeId) {
        database.update("UPDATE assistant_codes SET used_at = now() WHERE id = ? AND used_at IS NULL", codeId);
    }

    /** Kode yang sudah lewat sehari tidak berguna lagi, juga untuk mengenali pemakaian ulang. */
    public void deleteStaleCodes() {
        database.update("DELETE FROM assistant_codes WHERE expires_at < now() - interval '1 day'");
    }

    // -----------------------------------------------------------------
    // Sambungan
    // -----------------------------------------------------------------

    public void insertSession(UUID id, UUID tenantId, UUID userId, UUID codeId, String robotName, String machineName,
                              String clientVersion, String refreshHash, OffsetDateTime expiresAt) {
        database.update("""
                INSERT INTO assistant_sessions
                    (id, tenant_id, user_id, code_id, robot_name, machine_name, client_version, refresh_hash,
                     expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, userId, codeId, robotName, machineName, clientVersion, refreshHash, expiresAt);
    }

    /** Sambungan pemilik refresh token itu, dikunci untuk rotasi. */
    public Optional<Map<String, Object>> lockByRefresh(String refreshHash) {
        return database.queryRow("""
                SELECT id, tenant_id, user_id, robot_name, machine_name,
                       revoked_at IS NULL AND expires_at > now() AS live
                  FROM assistant_sessions
                 WHERE refresh_hash = ?
                   FOR UPDATE
                """, refreshHash);
    }

    /** Sambungan yang refresh token LAMA-nya itu — yang sudah diganti rotasi. */
    public Optional<UUID> findByPreviousRefresh(String refreshHash) {
        return database.queryScalar("SELECT id FROM assistant_sessions WHERE previous_refresh_hash = ?", refreshHash)
                .map(Uuids::fromColumn);
    }

    public void rotate(UUID sessionId, String newRefreshHash, String oldRefreshHash, OffsetDateTime expiresAt) {
        database.update("""
                UPDATE assistant_sessions
                   SET refresh_hash = ?, previous_refresh_hash = ?, expires_at = ?, last_used_at = now()
                 WHERE id = ?
                """, newRefreshHash, oldRefreshHash, expiresAt, sessionId);
    }

    /** @return 1 kalau sambungan itu baru saja dicabut, 0 kalau sudah dicabut sebelumnya */
    public int revoke(UUID sessionId, String reason) {
        return database.update("""
                UPDATE assistant_sessions SET revoked_at = now(), revoke_reason = ?
                 WHERE id = ? AND revoked_at IS NULL
                """, reason, sessionId);
    }

    /** Hanya sambungan milik pengguna itu sendiri. */
    public int revokeOwn(UUID tenantId, UUID userId, UUID sessionId, String reason) {
        return database.update("""
                UPDATE assistant_sessions SET revoked_at = now(), revoke_reason = ?
                 WHERE id = ? AND tenant_id = ? AND user_id = ? AND revoked_at IS NULL
                """, reason, sessionId, tenantId, userId);
    }

    public int revokeFromCode(UUID codeId, String reason) {
        return database.update("""
                UPDATE assistant_sessions SET revoked_at = now(), revoke_reason = ?
                 WHERE code_id = ? AND revoked_at IS NULL
                """, reason, codeId);
    }

    public int revokeAllOfUser(UUID tenantId, String username, String reason) {
        return database.update("""
                UPDATE assistant_sessions SET revoked_at = now(), revoke_reason = ?
                 WHERE tenant_id = ? AND revoked_at IS NULL
                   AND user_id = (SELECT id FROM users WHERE tenant_id = ? AND username = ?)
                """, reason, tenantId, tenantId, username);
    }

    /**
     * Masih berlaku? Sekalian mencatat kapan terakhir dipakai — yang tampil di
     * daftar "Open Assistant tersambung" pemiliknya.
     */
    public boolean touchIfActive(UUID tenantId, UUID sessionId) {
        return database.update("""
                UPDATE assistant_sessions SET last_used_at = now()
                 WHERE id = ? AND tenant_id = ? AND revoked_at IS NULL AND expires_at > now()
                """, sessionId, tenantId) == 1;
    }

    /** Sambungan yang masih berlaku milik seorang pengguna, yang terakhir dipakai lebih dulu. */
    public List<Map<String, Object>> findActiveOfUser(UUID tenantId, UUID userId) {
        return database.queryRows("""
                SELECT id, machine_name, robot_name, client_version, created_at, last_used_at, expires_at
                  FROM assistant_sessions
                 WHERE tenant_id = ? AND user_id = ? AND revoked_at IS NULL AND expires_at > now()
                 ORDER BY last_used_at DESC
                """, tenantId, userId);
    }

    /** Yang sudah dicabut atau kedaluwarsa lebih dari 30 hari tidak perlu disimpan lagi. */
    public void deleteStaleSessions() {
        database.update("""
                DELETE FROM assistant_sessions
                 WHERE COALESCE(revoked_at, expires_at) < now() - interval '30 days'
                """);
    }
}
