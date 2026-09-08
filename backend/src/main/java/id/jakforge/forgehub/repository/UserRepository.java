package id.jakforge.forgehub.repository;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pengguna, penyewa, peran, dan lisensi.
 *
 * <p>Kolom {@code password_hash} hanya ikut pada satu metode — {@link
 * #untukMasuk(String)} — dan tidak pada daftar mana pun. Ringkasan yang bocor
 * masih bisa ditebak di luar sini tanpa batas percobaan dan tanpa ada yang tahu.
 */
@Repository
public class UserRepository {

    private final Db db;

    public UserRepository(Db db) {
        this.db = db;
    }

    // -----------------------------------------------------------------
    // Masuk
    // -----------------------------------------------------------------

    /** Satu-satunya tempat password_hash dibaca. */
    public Map<String, Object> untukMasuk(String username) {
        return db.row("""
                SELECT u.id, u.username, u.password_hash, u.display_name, u.role, u.is_active,
                       t.id AS tenant_id, t.name AS tenant_name
                  FROM users u
                  JOIN tenants t ON t.id = u.tenant_id
                 WHERE u.username = ?
                """, username);
    }

    public String hashSandi(UUID userId) {
        Object v = db.scalar("SELECT password_hash FROM users WHERE id = ?", userId);
        return v == null ? null : String.valueOf(v);
    }

    public void catatMasuk(UUID userId) {
        db.exec("UPDATE users SET last_login_at = now() WHERE id = ?", userId);
    }

    public void gantiSandi(UUID userId, String hash) {
        db.exec("UPDATE users SET password_hash = ? WHERE id = ?", hash, userId);
    }

    public void gantiSandiPengguna(UUID tenantId, String username, String hash) {
        db.exec("UPDATE users SET password_hash = ? WHERE tenant_id = ? AND username = ?",
                hash, tenantId, username);
    }

    // -----------------------------------------------------------------
    // Pengguna
    // -----------------------------------------------------------------

    public Map<String, Object> profil(UUID userId, UUID tenantId) {
        return db.row("""
                SELECT u.id, u.username, u.display_name, u.email, u.role, u.is_active,
                       u.created_at, u.last_login_at, t.name AS tenant_name
                  FROM users u
                  JOIN tenants t ON t.id = u.tenant_id
                 WHERE u.id = ? AND u.tenant_id = ?
                """, userId, tenantId);
    }

    public List<Map<String, Object>> semua(UUID tenantId) {
        return db.rows("""
                SELECT id, username, display_name, email, role, is_active, created_at, last_login_at
                  FROM users
                 WHERE tenant_id = ?
                 ORDER BY username
                """, tenantId);
    }

    public boolean ada(UUID tenantId, String username) {
        return db.exists("SELECT count(*) FROM users WHERE tenant_id = ? AND username = ?",
                tenantId, username);
    }

    /** COALESCE: medan yang tidak dikirim tidak menghapus nilai yang sudah ada. */
    public void perbarui(UUID tenantId, String username, String namaTampil, String surel,
                         String peran, boolean aktif) {
        db.exec("""
                UPDATE users
                   SET display_name = COALESCE(?, display_name),
                       email = COALESCE(?, email),
                       role = COALESCE(?, role),
                       is_active = ?
                 WHERE tenant_id = ? AND username = ?
                """, namaTampil, surel, peran, aktif, tenantId, username);
    }

    public void buat(UUID tenantId, String username, String hash, String namaTampil,
                     String surel, String peran, boolean aktif) {
        db.exec("""
                INSERT INTO users (id, tenant_id, username, password_hash, display_name,
                                   email, role, is_active, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                """, Db.newId(), tenantId, username, hash, namaTampil, surel, peran, aktif);
    }

    public int hapus(UUID tenantId, String username) {
        return db.exec("DELETE FROM users WHERE tenant_id = ? AND username = ?", tenantId, username);
    }

    public boolean administrator(UUID tenantId, String username) {
        return db.exists("""
                SELECT count(*) FROM users
                 WHERE tenant_id = ? AND username = ? AND role = 'Administrator'
                """, tenantId, username);
    }

    public long jumlahAdministratorAktif(UUID tenantId) {
        return db.count("""
                SELECT count(*) FROM users
                 WHERE tenant_id = ? AND role = 'Administrator' AND is_active
                """, tenantId);
    }

    public long jumlah(UUID tenantId) {
        return db.count("SELECT count(*) FROM users WHERE tenant_id = ?", tenantId);
    }

    // -----------------------------------------------------------------
    // Penyewa, peran, lisensi
    // -----------------------------------------------------------------

    public UUID tenantBernama(String nama) {
        Object id = db.scalar("SELECT id FROM tenants WHERE name = ?", nama);
        if (id == null) return null;

        return id instanceof UUID u ? u : Db.uuid(String.valueOf(id));
    }

    public String namaTenant(UUID tenantId) {
        Object v = db.scalar("SELECT name FROM tenants WHERE id = ?", tenantId);
        return v == null ? null : String.valueOf(v);
    }

    public List<Map<String, Object>> penyewa() {
        return db.rows("""
                SELECT t.id, t.name, t.display_name, t.created_at,
                       (SELECT count(*) FROM users u WHERE u.tenant_id = t.id)  AS user_count,
                       (SELECT count(*) FROM robots r WHERE r.tenant_id = t.id) AS robot_count
                  FROM tenants t
                 ORDER BY t.name
                """);
    }

    public List<Map<String, Object>> peran(UUID tenantId) {
        return db.rows("""
                SELECT r.id, r.name, r.description, r.permissions, r.created_at,
                       (SELECT count(*) FROM users u
                         WHERE u.tenant_id = r.tenant_id AND u.role = r.name) AS user_count
                  FROM roles r
                 WHERE r.tenant_id = ?
                 ORDER BY r.name
                """, tenantId);
    }

    public List<Map<String, Object>> lisensi(UUID tenantId) {
        return db.rows("""
                SELECT id, product, total, used, expires_at
                  FROM licenses WHERE tenant_id = ? ORDER BY product
                """, tenantId);
    }
}
