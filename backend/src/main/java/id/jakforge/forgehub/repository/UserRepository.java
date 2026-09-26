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
     * <p>Terpisah dari {@link #perbarui}, yang juga menulis peran dan status
     * aktif: memakainya di sini berarti satu medan yang lupa disaring cukup
     * untuk membuat siapa pun menjadi Administrator.
     *
     * <p>Tanpa COALESCE, berbeda dengan perbarui: surel yang dikosongkan
     * memang dimaksudkan untuk dihapus.
     */
    public int ubahProfil(UUID userId, UUID tenantId, String namaTampil, String surel) {
        return db.exec("""
                UPDATE users
                   SET display_name = ?,
                       email = ?
                 WHERE id = ? AND tenant_id = ?
                """, namaTampil, surel, userId, tenantId);
    }

    /**
     * {@code folders}: nama folder bersama tempat penggunanya ditugaskan, supaya
     * halaman pengguna bisa menunjukkan siapa melihat apa tanpa membuka setiap
     * folder satu per satu.
     */
    public List<Map<String, Object>> semua(UUID tenantId) {
        return db.rows("""
                SELECT id, username, display_name, email, role, is_active, created_at, last_login_at,
                       (SELECT array_agg(f.name ORDER BY lower(f.name))
                          FROM folder_users fu JOIN folders f ON f.id = fu.folder_id
                         WHERE fu.user_id = users.id) AS folders
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

    /**
     * Peran seseorang SAAT INI dan izinnya — bukan peran yang tertulis di
     * tokennya. LEFT JOIN: pengguna dengan peran yang barisnya tidak ada tetap
     * ditemukan, dengan izin kosong.
     */
    public Map<String, Object> izinPengguna(UUID userId, UUID tenantId) {
        return db.row("""
                SELECT u.role, u.is_active, r.permissions
                  FROM users u
                  LEFT JOIN roles r ON r.tenant_id = u.tenant_id AND r.name = u.role
                 WHERE u.id = ? AND u.tenant_id = ?
                """, userId, tenantId);
    }

    /** Peran bernama itu, atau null. Nama dicocokkan tanpa membedakan huruf besar. */
    public Map<String, Object> peranSatu(UUID tenantId, String nama) {
        return db.row("""
                SELECT id, name, description, permissions
                  FROM roles WHERE tenant_id = ? AND lower(name) = lower(?)
                """, tenantId, nama);
    }

    public void buatPeran(UUID tenantId, String nama, String keterangan, String izin) {
        db.exec("""
                INSERT INTO roles (id, tenant_id, name, description, permissions, created_at)
                VALUES (?, ?, ?, ?, ?, now())
                """, Db.newId(), tenantId, nama, keterangan, izin);
    }

    public int ubahPeran(UUID tenantId, String namaLama, String nama, String keterangan, String izin) {
        return db.exec("""
                UPDATE roles SET name = ?, description = ?, permissions = ?
                 WHERE tenant_id = ? AND name = ?
                """, nama, keterangan, izin, tenantId, namaLama);
    }

    /** Pengguna menyimpan NAMA perannya, jadi ganti nama peran ikut mengganti milik mereka. */
    public int gantiNamaPeranPengguna(UUID tenantId, String namaLama, String nama) {
        return db.exec("UPDATE users SET role = ? WHERE tenant_id = ? AND role = ?", nama, tenantId, namaLama);
    }

    public long jumlahPemakaiPeran(UUID tenantId, String nama) {
        return db.count("SELECT count(*) FROM users WHERE tenant_id = ? AND role = ?", tenantId, nama);
    }

    public int hapusPeran(UUID tenantId, String nama) {
        return db.exec("DELETE FROM roles WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    /** Peran pengguna itu, atau null kalau penggunanya tidak ada. */
    public String peranPengguna(UUID tenantId, String username) {
        Object v = db.scalar("SELECT role FROM users WHERE tenant_id = ? AND username = ?", tenantId, username);
        return v == null ? null : String.valueOf(v);
    }

    /** Aktif, berperan Administrator — untuk penjagaan "Administrator terakhir". */
    public boolean administratorAktif(UUID tenantId, String username) {
        return db.exists("""
                SELECT count(*) FROM users
                 WHERE tenant_id = ? AND username = ? AND role = 'Administrator' AND is_active
                """, tenantId, username);
    }

    public List<Map<String, Object>> lisensi(UUID tenantId) {
        return db.rows("""
                SELECT id, product, total, used, expires_at
                  FROM licenses WHERE tenant_id = ? ORDER BY product
                """, tenantId);
    }
}
