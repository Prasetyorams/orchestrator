package id.jakforge.forgehub.repository;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Proses dan paket.
 *
 * <p>Digabung karena keduanya satu hal di mata pemakainya: menerbitkan paket
 * dari Studio membuat prosesnya sekaligus, dan menghapus paket terakhir sebuah
 * proses membuat prosesnya tidak bisa dijalankan. Memisahkannya berarti aturan
 * itu tinggal di lapisan service dan kedua repository tidak pernah tahu bahwa
 * mereka saling terkait.
 */
@Repository
public class CatalogRepository {

    private final Db db;

    public CatalogRepository(Db db) {
        this.db = db;
    }

    // -----------------------------------------------------------------
    // Proses
    // -----------------------------------------------------------------

    public List<Map<String, Object>> proses(UUID tenantId) {
        return db.rows("""
                SELECT p.id, p.name, p.package_name, p.package_version, p.environment,
                       p.description, p.created_at,
                       (SELECT count(*) FROM jobs j
                         WHERE j.tenant_id = p.tenant_id AND j.process_name = p.name) AS job_count,
                       (SELECT max(j.created_at) FROM jobs j
                         WHERE j.tenant_id = p.tenant_id AND j.process_name = p.name) AS last_run_at
                  FROM processes p
                 WHERE p.tenant_id = ?
                 ORDER BY p.name
                """, tenantId);
    }

    public boolean adaProses(UUID tenantId, String nama) {
        return db.exists("SELECT count(*) FROM processes WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    /** COALESCE: medan yang tidak dikirim tidak menghapus nilai yang sudah ada. */
    public void perbaruiProses(UUID tenantId, String nama, String paket, String versi,
                               String lingkungan, String keterangan) {
        db.exec("""
                UPDATE processes
                   SET package_name = COALESCE(?, package_name),
                       package_version = COALESCE(?, package_version),
                       environment = COALESCE(?, environment),
                       description = COALESCE(?, description)
                 WHERE tenant_id = ? AND name = ?
                """, paket, versi, lingkungan, keterangan, tenantId, nama);
    }

    public void buatProses(UUID tenantId, String nama, String paket, String versi,
                           String lingkungan, String keterangan) {
        db.exec("""
                INSERT INTO processes
                    (id, tenant_id, name, package_name, package_version, environment, description, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                """, Db.newId(), tenantId, nama, paket, versi, lingkungan, keterangan);
    }

    /** Dipakai penerbitan paket: menyetel paket dan versinya tanpa menyentuh sisanya. */
    public void tautkanPaket(UUID tenantId, String nama, String paket, String versi) {
        db.exec("""
                UPDATE processes SET package_name = ?, package_version = ?
                 WHERE tenant_id = ? AND name = ?
                """, paket, versi, tenantId, nama);
    }

    public int hapusProses(UUID tenantId, String nama) {
        return db.exec("DELETE FROM processes WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    // -----------------------------------------------------------------
    // Paket
    // -----------------------------------------------------------------

    /**
     * Daftar paket TANPA isinya.
     *
     * <p>Kolom content sengaja tidak diambil: satu paket belasan kilobita
     * dikalikan seluruh riwayat versi akan menyeret seluruh gudang tiap kali
     * halaman dibuka, dan tidak ada satu pun yang menampilkannya.
     */
    public List<Map<String, Object>> paket(UUID tenantId) {
        return db.rows("""
                SELECT id, name, version, description, entry_point, published_by, published_at, size_bytes
                  FROM packages
                 WHERE tenant_id = ?
                 ORDER BY name, published_at DESC
                """, tenantId);
    }

    public boolean adaPaket(UUID tenantId, String nama, String versi) {
        return db.exists("""
                SELECT count(*) FROM packages WHERE tenant_id = ? AND name = ? AND version = ?
                """, tenantId, nama, versi);
    }

    /**
     * COALESCE pada content: penerbitan ulang yang hanya memperbarui keterangan
     * tidak boleh MENGHAPUS isi paket yang sudah ada.
     */
    public void perbaruiPaket(UUID tenantId, String nama, String versi, String keterangan,
                              String entryPoint, String penerbit, long ukuran, byte[] isi) {
        db.exec("""
                UPDATE packages
                   SET description = ?, entry_point = ?, published_by = ?,
                       published_at = now(), size_bytes = ?,
                       content = COALESCE(?, content)
                 WHERE tenant_id = ? AND name = ? AND version = ?
                """, keterangan, entryPoint, penerbit, ukuran, isi, tenantId, nama, versi);
    }

    public void buatPaket(UUID tenantId, String nama, String versi, String keterangan,
                          String entryPoint, String penerbit, long ukuran, byte[] isi) {
        db.exec("""
                INSERT INTO packages
                    (id, tenant_id, name, version, description, entry_point,
                     published_by, published_at, size_bytes, content)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, ?)
                """, Db.newId(), tenantId, nama, versi, keterangan, entryPoint, penerbit, ukuran, isi);
    }

    /**
     * Isi paket sebagai bita mentah.
     *
     * <p>Kueri tersendiri, bukan lewat Db.rows: pemetaan baris sengaja mengubah
     * BYTEA menjadi ukurannya, karena isi paket tidak pernah benar dikirim
     * sebagai bagian dari JSON.
     */
    public byte[] isiPaket(UUID tenantId, String nama, String versi) {
        List<byte[]> hasil = db.jdbc().query("""
                SELECT content FROM packages WHERE tenant_id = ? AND name = ? AND version = ?
                """, (rs, i) -> rs.getBytes(1), tenantId, nama, versi);

        return hasil.isEmpty() ? null : hasil.get(0);
    }

    public int hapusPaket(UUID tenantId, String nama, String versi) {
        return db.exec("DELETE FROM packages WHERE tenant_id = ? AND name = ? AND version = ?",
                tenantId, nama, versi);
    }
}
