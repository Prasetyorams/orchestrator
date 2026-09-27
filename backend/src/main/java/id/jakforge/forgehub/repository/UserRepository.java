package id.jakforge.forgehub.repository;

import id.jakforge.forgehub.common.Uuids;
import id.jakforge.forgehub.model.RoleNames;
import id.jakforge.forgehub.model.UserAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pengguna.
 *
 * <p>Kolom {@code password_hash} hanya ikut pada dua metode — {@link
 * #findForLogin(String)} dan {@link #findPasswordHash(UUID)} — dan tidak pada
 * daftar mana pun. Ringkasan yang bocor masih bisa ditebak di luar sini tanpa
 * batas percobaan dan tanpa ada yang tahu.
 */
@Repository
@RequiredArgsConstructor
public class UserRepository {

    private final Database database;

    // -----------------------------------------------------------------
    // Masuk
    // -----------------------------------------------------------------

    /** Pengguna beserta hash kata sandinya, untuk memeriksa login. */
    public Optional<Map<String, Object>> findForLogin(String username) {
        return database.queryRow("""
                SELECT u.id, u.username, u.password_hash, u.display_name, u.role, u.is_active,
                       t.id AS tenant_id, t.name AS tenant_name
                  FROM users u
                  JOIN tenants t ON t.id = u.tenant_id
                 WHERE u.username = ?
                """, username);
    }

    public Optional<String> findPasswordHash(UUID userId) {
        return database.queryScalar("SELECT password_hash FROM users WHERE id = ?", userId).map(String::valueOf);
    }

    public void recordLogin(UUID userId) {
        database.update("UPDATE users SET last_login_at = now() WHERE id = ?", userId);
    }

    public void updatePasswordHash(UUID userId, String passwordHash) {
        database.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordHash, userId);
    }

    public void updatePasswordHashByUsername(UUID tenantId, String username, String passwordHash) {
        database.update("UPDATE users SET password_hash = ? WHERE tenant_id = ? AND username = ?",
                passwordHash, tenantId, username);
    }

    /** Penyewa pemilik nama pengguna itu — untuk mencatat masuk, saat belum ada token. */
    public Optional<UUID> findTenantIdByUsername(String username) {
        return database.queryScalar("SELECT tenant_id FROM users WHERE username = ? LIMIT 1", username)
                .map(Uuids::fromColumn);
    }

    // -----------------------------------------------------------------
    // Pengguna
    // -----------------------------------------------------------------

    public Optional<Map<String, Object>> findProfile(UUID userId, UUID tenantId) {
        return database.queryRow("""
                SELECT u.id, u.username, u.display_name, u.email, u.role, u.is_active,
                       u.created_at, u.last_login_at, t.name AS tenant_name,
                       t.display_name AS tenant_display_name
                  FROM users u
                  JOIN tenants t ON t.id = u.tenant_id
                 WHERE u.id = ? AND u.tenant_id = ?
                """, userId, tenantId);
    }

    /**
     * Profil yang diubah pemiliknya sendiri.
     *
     * <p>Terpisah dari {@link #update}, yang juga menulis peran dan status
     * aktif: memakainya di sini berarti satu medan yang lupa disaring cukup
     * untuk membuat siapa pun menjadi Administrator.
     *
     * <p>Tanpa COALESCE, berbeda dengan update: surel yang dikosongkan
     * memang dimaksudkan untuk dihapus.
     */
    public int updateProfile(UUID userId, UUID tenantId, String displayName, String email) {
        return database.update("""
                UPDATE users
                   SET display_name = ?,
                       email = ?
                 WHERE id = ? AND tenant_id = ?
                """, displayName, email, userId, tenantId);
    }

    /**
     * {@code folders}: nama folder bersama tempat penggunanya ditugaskan, supaya
     * halaman pengguna bisa menunjukkan siapa melihat apa tanpa membuka setiap
     * folder satu per satu.
     */
    public List<Map<String, Object>> findAll(UUID tenantId) {
        return database.queryRows("""
                SELECT id, username, display_name, email, role, is_active, created_at, last_login_at,
                       (SELECT array_agg(f.name ORDER BY lower(f.name))
                          FROM folder_users fu JOIN folders f ON f.id = fu.folder_id
                         WHERE fu.user_id = users.id) AS folders
                  FROM users
                 WHERE tenant_id = ?
                 ORDER BY username
                """, tenantId);
    }

    public boolean existsByUsername(UUID tenantId, String username) {
        return database.exists("SELECT count(*) FROM users WHERE tenant_id = ? AND username = ?",
                tenantId, username);
    }

    public Optional<UUID> findIdByUsername(UUID tenantId, String username) {
        return database.queryScalar("SELECT id FROM users WHERE tenant_id = ? AND username = ?", tenantId, username)
                .map(Uuids::fromColumn);
    }

    /** COALESCE: medan yang tidak dikirim tidak menghapus nilai yang sudah ada. */
    public void update(UUID tenantId, String username, String displayName, String email,
                       String role, boolean active) {
        database.update("""
                UPDATE users
                   SET display_name = COALESCE(?, display_name),
                       email = COALESCE(?, email),
                       role = COALESCE(?, role),
                       is_active = ?
                 WHERE tenant_id = ? AND username = ?
                """, displayName, email, role, active, tenantId, username);
    }

    public void insert(UUID tenantId, String username, String passwordHash, String displayName,
                       String email, String role, boolean active) {
        database.update("""
                INSERT INTO users (id, tenant_id, username, password_hash, display_name,
                                   email, role, is_active, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), tenantId, username, passwordHash, displayName, email, role, active);
    }

    public int deleteByUsername(UUID tenantId, String username) {
        return database.update("DELETE FROM users WHERE tenant_id = ? AND username = ?", tenantId, username);
    }

    // -----------------------------------------------------------------
    // Peran pengguna
    // -----------------------------------------------------------------

    /**
     * Peran seseorang SAAT INI dan izinnya — bukan peran yang tertulis di
     * tokennya. LEFT JOIN: pengguna dengan peran yang barisnya tidak ada tetap
     * ditemukan, dengan izin kosong.
     */
    public Optional<UserAccess> findAccess(UUID userId, UUID tenantId) {
        return database.query("""
                SELECT u.role, u.is_active, r.permissions
                  FROM users u
                  LEFT JOIN roles r ON r.tenant_id = u.tenant_id AND r.name = u.role
                 WHERE u.id = ? AND u.tenant_id = ?
                """, (rs, rowNumber) -> new UserAccess(rs.getString(1), rs.getBoolean(2), rs.getString(3)),
                userId, tenantId).stream().findFirst();
    }

    /** Peran pengguna itu, atau kosong kalau penggunanya tidak ada. */
    public Optional<String> findRole(UUID tenantId, String username) {
        return database.queryScalar("SELECT role FROM users WHERE tenant_id = ? AND username = ?", tenantId, username)
                .map(String::valueOf);
    }

    public boolean isAdministrator(UUID tenantId, String username) {
        return database.exists("""
                SELECT count(*) FROM users
                 WHERE tenant_id = ? AND username = ? AND role = ?
                """, tenantId, username, RoleNames.ADMINISTRATOR);
    }

    /** Aktif, berperan Administrator — untuk penjagaan "Administrator terakhir". */
    public boolean isActiveAdministrator(UUID tenantId, String username) {
        return database.exists("""
                SELECT count(*) FROM users
                 WHERE tenant_id = ? AND username = ? AND role = ? AND is_active
                """, tenantId, username, RoleNames.ADMINISTRATOR);
    }

    public long countActiveAdministrators(UUID tenantId) {
        return database.count("""
                SELECT count(*) FROM users
                 WHERE tenant_id = ? AND role = ? AND is_active
                """, tenantId, RoleNames.ADMINISTRATOR);
    }

    public long countByRole(UUID tenantId, String role) {
        return database.count("SELECT count(*) FROM users WHERE tenant_id = ? AND role = ?", tenantId, role);
    }

    /** Pengguna menyimpan NAMA perannya, jadi ganti nama peran ikut mengganti milik mereka. */
    public int replaceRole(UUID tenantId, String oldRole, String newRole) {
        return database.update("UPDATE users SET role = ? WHERE tenant_id = ? AND role = ?", newRole, tenantId, oldRole);
    }
}
