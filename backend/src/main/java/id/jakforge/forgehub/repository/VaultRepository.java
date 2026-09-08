package id.jakforge.forgehub.repository;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aset, kredensial, dan gudang berkas.
 *
 * <p>Digabung karena ketiganya satu golongan: nilai yang disimpan ForgeHub
 * untuk dipakai robot, bukan hasil kerja robot. Yang membedakan cuma bentuk
 * isinya — teks, kata sandi, atau berkas.
 *
 * <p>Nilai rahasia disimpan SUDAH tersandi. Repository ini tidak tahu cara
 * membukanya dan memang tidak perlu tahu: penyandiannya urusan service, dan
 * kunci yang dipegang lapisan data berarti setiap kueri berpotensi membocorkan
 * nilai polos.
 */
@Repository
public class VaultRepository {

    private final Db db;

    public VaultRepository(Db db) {
        this.db = db;
    }

    // -----------------------------------------------------------------
    // Aset
    // -----------------------------------------------------------------

    /** Nilai aset rahasia TIDAK ikut; yang muncul hanya penanda bahwa isinya ada. */
    public List<Map<String, Object>> aset(UUID tenantId) {
        return db.rows("""
                SELECT id, name, type, scope, description, created_at, updated_at,
                       CASE WHEN type IN ('Credential', 'Secret') THEN NULL ELSE value_text END AS value_text,
                       CASE WHEN value_text IS NULL OR value_text = '' THEN FALSE ELSE TRUE END AS has_value
                  FROM assets
                 WHERE tenant_id = ?
                 ORDER BY name
                """, tenantId);
    }

    public Map<String, Object> nilaiAset(UUID tenantId, String nama) {
        return db.row("SELECT type, value_text FROM assets WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    public boolean adaAset(UUID tenantId, String nama) {
        return db.exists("SELECT count(*) FROM assets WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    public void perbaruiAset(UUID tenantId, String nama, String tipe, String nilai,
                             String keterangan, String cakupan) {
        db.exec("""
                UPDATE assets
                   SET type = ?, value_text = ?, description = ?, scope = ?, updated_at = now()
                 WHERE tenant_id = ? AND name = ?
                """, tipe, nilai, keterangan, cakupan, tenantId, nama);
    }

    public void buatAset(UUID tenantId, String nama, String tipe, String nilai,
                         String keterangan, String cakupan) {
        db.exec("""
                INSERT INTO assets (id, tenant_id, name, type, value_text, description, scope,
                                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())
                """, Db.newId(), tenantId, nama, tipe, nilai, keterangan, cakupan);
    }

    public int hapusAset(UUID tenantId, String nama) {
        return db.exec("DELETE FROM assets WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }


    // -----------------------------------------------------------------
    // Kredensial
    // -----------------------------------------------------------------

    /** Kolom password_enc sengaja tidak ikut dipilih. */
    public List<Map<String, Object>> kredensial(UUID tenantId) {
        return db.rows("""
                SELECT id, name, username, description, created_at
                  FROM credentials
                 WHERE tenant_id = ?
                 ORDER BY name
                """, tenantId);
    }

    public Map<String, Object> nilaiKredensial(UUID tenantId, String nama) {
        return db.row("SELECT username, password_enc FROM credentials WHERE tenant_id = ? AND name = ?",
                tenantId, nama);
    }

    public boolean adaKredensial(UUID tenantId, String nama) {
        return db.exists("SELECT count(*) FROM credentials WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    /**
     * COALESCE pada password_enc: menyunting keterangan tanpa mengisi ulang
     * kata sandinya tidak boleh MENGHAPUS kata sandi yang ada. Yang bersangkutan
     * baru tahu saat robot berikutnya gagal masuk.
     */
    public void perbaruiKredensial(UUID tenantId, String nama, String pengguna,
                                   String sandiTersandi, String keterangan) {
        db.exec("""
                UPDATE credentials
                   SET username = ?, password_enc = COALESCE(?, password_enc), description = ?
                 WHERE tenant_id = ? AND name = ?
                """, pengguna, sandiTersandi, keterangan, tenantId, nama);
    }

    public void buatKredensial(UUID tenantId, String nama, String pengguna,
                               String sandiTersandi, String keterangan) {
        db.exec("""
                INSERT INTO credentials (id, tenant_id, name, username, password_enc, description, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, Db.newId(), tenantId, nama, pengguna, sandiTersandi, keterangan);
    }

    public int hapusKredensial(UUID tenantId, String nama) {
        return db.exec("DELETE FROM credentials WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    // -----------------------------------------------------------------
    // Gudang berkas
    // -----------------------------------------------------------------

    public List<Map<String, Object>> gudang(UUID tenantId) {
        return db.rows("""
                SELECT b.id, b.name, b.description, b.created_at,
                       count(f.id)                    AS file_count,
                       COALESCE(sum(f.size_bytes), 0) AS total_bytes
                  FROM buckets b
                  LEFT JOIN bucket_files f
                         ON f.tenant_id = b.tenant_id AND f.bucket_name = b.name
                 WHERE b.tenant_id = ?
                 GROUP BY b.id, b.name, b.description, b.created_at
                 ORDER BY b.name
                """, tenantId);
    }

    public boolean adaGudang(UUID tenantId, String nama) {
        return db.exists("SELECT count(*) FROM buckets WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    public void buatGudang(UUID tenantId, String nama, String keterangan) {
        db.exec("""
                INSERT INTO buckets (id, tenant_id, name, description, created_at)
                VALUES (?, ?, ?, ?, now())
                """, Db.newId(), tenantId, nama, keterangan);
    }

    public int hapusGudang(UUID tenantId, String nama) {
        return db.exec("DELETE FROM buckets WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    public List<Map<String, Object>> berkas(UUID tenantId, String gudang) {
        return db.rows("""
                SELECT id, file_name, content_type, size_bytes, uploaded_by, uploaded_at
                  FROM bucket_files
                 WHERE tenant_id = ? AND bucket_name = ?
                 ORDER BY uploaded_at DESC
                """, tenantId, gudang);
    }

    /** Berkas bernama sama dibuang lebih dulu: diganti, bukan ditumpuk. */
    public void hapusBerkasBernama(UUID tenantId, String gudang, String namaBerkas) {
        db.exec("DELETE FROM bucket_files WHERE tenant_id = ? AND bucket_name = ? AND file_name = ?",
                tenantId, gudang, namaBerkas);
    }

    public void simpanBerkas(UUID tenantId, String gudang, String namaBerkas, String tipeIsi,
                             long ukuran, String pengunggah, byte[] isi) {
        db.exec("""
                INSERT INTO bucket_files
                    (id, tenant_id, bucket_name, file_name, content_type, size_bytes,
                     uploaded_by, uploaded_at, content)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?)
                """, Db.newId(), tenantId, gudang, namaBerkas, tipeIsi, ukuran, pengunggah, isi);
    }

    /** Isi berkas: kueri tersendiri, dengan alasan yang sama seperti isi paket. */
    public Map<String, Object> isiBerkas(UUID tenantId, String gudang, UUID id) {
        List<Map<String, Object>> hasil = db.jdbc().query("""
                SELECT file_name, content_type, content FROM bucket_files
                 WHERE tenant_id = ? AND bucket_name = ? AND id = ?
                """, (rs, i) -> Map.of(
                        "fileName", rs.getString(1),
                        "contentType", rs.getString(2) == null ? "application/octet-stream" : rs.getString(2),
                        "content", rs.getBytes(3) == null ? new byte[0] : rs.getBytes(3)),
                tenantId, gudang, id);

        return hasil.isEmpty() ? null : hasil.get(0);
    }

    public int hapusBerkas(UUID tenantId, String gudang, UUID id) {
        return db.exec("DELETE FROM bucket_files WHERE tenant_id = ? AND bucket_name = ? AND id = ?",
                tenantId, gudang, id);
    }
}
