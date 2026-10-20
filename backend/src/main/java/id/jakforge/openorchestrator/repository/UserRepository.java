package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.model.RoleNames;
import id.jakforge.openorchestrator.model.UserAccess;
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

    /** Pengguna beserta hash kata sandinya, untuk memeriksa login. authProvider AD = tidak boleh masuk lokal. */
    public Optional<Map<String, Object>> findForLogin(String username) {
        return database.queryRow("""
                SELECT u.id, u.username, u.password_hash, u.display_name, u.role, u.is_active, u.auth_provider,
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

    // -----------------------------------------------------------------
    // Akun Windows AD (V19)
    // -----------------------------------------------------------------

    private static final String AD_LOGIN_COLUMNS = """
            SELECT u.id, u.username, u.display_name, u.role, u.is_active, u.ad_username, u.ad_object_guid,
                   t.id AS tenant_id, t.name AS tenant_name
              FROM users u
              JOIN tenants t ON t.id = u.tenant_id
            """;

    /** Pengguna AD yang identitas STABIL-nya (objectGUID) ini — paling banyak satu (uq_users_ad_guid). */
    public Optional<Map<String, Object>> findAdUserByGuid(UUID objectGuid) {
        return database.queryRows(AD_LOGIN_COLUMNS + " WHERE u.auth_provider = 'AD' AND u.ad_object_guid = ?", objectGuid)
                .stream().findFirst();
    }

    /** Pengguna AD yang didaftarkan dengan nama AD ini — paling banyak satu (uq_users_ad_username). */
    public Optional<Map<String, Object>> findAdUserByAdUsername(String adUsername) {
        return database.queryRows(AD_LOGIN_COLUMNS + " WHERE u.auth_provider = 'AD' AND lower(u.ad_username) = lower(?)",
                adUsername).stream().findFirst();
    }

    /**
     * Tautkan identitas AD — HANYA kalau belum ada tautan (ad_object_guid
     * null). Dipakai saat masuk pertama untuk pengguna yang didaftarkan tanpa
     * akun layanan; dua masuk bersamaan tidak bisa menautkan dua identitas.
     */
    public boolean linkAdIdentityIfUnlinked(UUID userId, UUID objectGuid, String directory, String adUsername) {
        return database.update("""
                UPDATE users SET ad_object_guid = ?, ad_directory = ?, ad_username = ?, ad_linked_at = now()
                 WHERE id = ? AND auth_provider = 'AD' AND ad_object_guid IS NULL
                """, objectGuid, directory, adUsername, userId) == 1;
    }

    /** Nama AD diganti di direktori, identitasnya (GUID) sama. */
    public void updateAdUsername(UUID userId, String adUsername) {
        database.update("UPDATE users SET ad_username = ? WHERE id = ? AND auth_provider = 'AD'", adUsername, userId);
    }

    /** Metode masuk dan identitas AD seorang pengguna: authProvider, adUsername, adObjectGuid, isActive. */
    public Optional<Map<String, Object>> findAuthIdentity(UUID tenantId, String username) {
        return database.queryRow("""
                SELECT auth_provider, ad_username, ad_object_guid, is_active FROM users WHERE tenant_id = ? AND username = ?
                """, tenantId, username);
    }

    /** Pengguna (internal) yang sudah memakai identitas AD ini, selain {@code exceptUsername}. */
    public Optional<String> findUsernameUsingAdIdentity(String adUsername, UUID objectGuid, UUID tenantId,
                                                        String exceptUsername) {
        return database.queryScalar("""
                SELECT username FROM users
                 WHERE auth_provider = 'AD'
                   AND (lower(ad_username) = lower(?) OR (CAST(? AS uuid) IS NOT NULL AND ad_object_guid = ?))
                   AND NOT (tenant_id = ? AND username = ?)
                 LIMIT 1
                """, adUsername, objectGuid, objectGuid, tenantId, exceptUsername == null ? "" : exceptUsername)
                .map(String::valueOf);
    }

    /**
     * Jadikan pengguna akun AD: sandi lokal DIHAPUS (tidak ada lagi masuk
     * lokal), identitas AD diisi. {@code objectGuid} null = ditautkan saat
     * masuk pertama.
     */
    public void setAdIdentity(UUID tenantId, String username, String adUsername, UUID objectGuid, String directory,
                              String updatedBy) {
        database.update("""
                UPDATE users
                   SET auth_provider = 'AD', password_hash = NULL, ad_username = ?, ad_object_guid = ?,
                       ad_directory = ?, ad_linked_at = CASE WHEN CAST(? AS uuid) IS NULL THEN NULL ELSE now() END,
                       updated_at = now(), updated_by = ?
                 WHERE tenant_id = ? AND username = ?
                """, adUsername, objectGuid, objectGuid == null ? null : directory, objectGuid, updatedBy, tenantId, username);
    }

    /** Kembalikan ke akun lokal dengan sandi baru; identitas AD dilepas. */
    public void setLocalIdentity(UUID tenantId, String username, String passwordHash, String updatedBy) {
        database.update("""
                UPDATE users
                   SET auth_provider = 'LOCAL', password_hash = ?, ad_username = NULL, ad_object_guid = NULL,
                       ad_directory = NULL, ad_linked_at = NULL, updated_at = now(), updated_by = ?
                 WHERE tenant_id = ? AND username = ?
                """, passwordHash, updatedBy, tenantId, username);
    }

    /** Lepas tautan AD: nama AD tetap, GUID dikosongkan — ditautkan lagi saat masuk berikutnya atau saat didaftarkan ulang. */
    public int unlinkAdIdentity(UUID tenantId, String username, String updatedBy) {
        return database.update("""
                UPDATE users
                   SET ad_object_guid = NULL, ad_directory = NULL, ad_linked_at = NULL, updated_at = now(), updated_by = ?
                 WHERE tenant_id = ? AND username = ? AND auth_provider = 'AD'
                """, updatedBy, tenantId, username);
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
                       auth_provider, ad_username, ad_object_guid, ad_linked_at, created_by, updated_at, updated_by,
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
        update(tenantId, username, displayName, email, role, active, null);
    }

    public void update(UUID tenantId, String username, String displayName, String email,
                       String role, boolean active, String updatedBy) {
        database.update("""
                UPDATE users
                   SET display_name = COALESCE(?, display_name),
                       email = COALESCE(?, email),
                       role = COALESCE(?, role),
                       is_active = ?,
                       updated_at = now(),
                       updated_by = COALESCE(?, updated_by)
                 WHERE tenant_id = ? AND username = ?
                """, displayName, email, role, active, updatedBy, tenantId, username);
    }

    public void insert(UUID tenantId, String username, String passwordHash, String displayName,
                       String email, String role, boolean active) {
        insert(tenantId, username, passwordHash, displayName, email, role, active, null);
    }

    public void insert(UUID tenantId, String username, String passwordHash, String displayName,
                       String email, String role, boolean active, String createdBy) {
        database.update("""
                INSERT INTO users (id, tenant_id, username, password_hash, display_name,
                                   email, role, is_active, created_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), ?)
                """, UUID.randomUUID(), tenantId, username, passwordHash, displayName, email, role, active, createdBy);
    }

    /** Pengguna AD baru (V19): tanpa sandi lokal. {@code objectGuid} null = ditautkan saat masuk pertama. */
    public void insertAd(UUID tenantId, String username, String adUsername, UUID objectGuid, String directory,
                         String displayName, String email, String role, boolean active, String createdBy) {
        database.update("""
                INSERT INTO users (id, tenant_id, username, password_hash, display_name, email, role, is_active,
                                   created_at, created_by, auth_provider, ad_username, ad_object_guid, ad_directory,
                                   ad_linked_at)
                VALUES (?, ?, ?, NULL, ?, ?, ?, ?, now(), ?, 'AD', ?, ?, ?,
                        CASE WHEN CAST(? AS uuid) IS NULL THEN NULL ELSE now() END)
                """, UUID.randomUUID(), tenantId, username, displayName, email, role, active, createdBy, adUsername,
                objectGuid, objectGuid == null ? null : directory, objectGuid);
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
