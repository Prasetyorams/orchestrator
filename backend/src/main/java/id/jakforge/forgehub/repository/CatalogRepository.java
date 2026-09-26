package id.jakforge.forgehub.repository;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
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

    /**
     * Daftar proses, beserta pekerjaannya yang BELUM selesai.
     *
     * <p>active_jobs dan active_state yang membuat tombol Jalankan di halaman
     * Proses mati selama prosesnya masih berjalan, lalu hidup lagi begitu
     * selesai. Keadaannya dipilih yang paling jauh: RUNNING mengalahkan
     * STOPPING, STOPPING mengalahkan PENDING.
     *
     * <p>Pekerjaan dicocokkan lewat nama DAN folder: proses bernama sama di
     * folder lain adalah proses lain, dengan riwayatnya sendiri.
     */
    public List<Map<String, Object>> proses(UUID tenantId, UUID folderId) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String saring = "";

        if (folderId != null) {
            saring = " AND p.folder_id = ?";
            args.add(folderId);
        }

        return db.rows("""
                SELECT p.id, p.name, p.package_name, p.package_version, p.environment,
                       p.description, p.created_at, p.folder_id,
                       (SELECT count(*) FROM jobs j
                         WHERE j.tenant_id = p.tenant_id AND j.folder_id = p.folder_id
                           AND j.process_name = p.name) AS job_count,
                       (SELECT max(j.created_at) FROM jobs j
                         WHERE j.tenant_id = p.tenant_id AND j.folder_id = p.folder_id
                           AND j.process_name = p.name) AS last_run_at,
                       (SELECT count(*) FROM jobs j
                         WHERE j.tenant_id = p.tenant_id AND j.folder_id = p.folder_id
                           AND j.process_name = p.name
                           AND j.state IN ('PENDING', 'RUNNING', 'STOPPING')) AS active_jobs,
                       (SELECT j.state FROM jobs j
                         WHERE j.tenant_id = p.tenant_id AND j.folder_id = p.folder_id
                           AND j.process_name = p.name
                           AND j.state IN ('PENDING', 'RUNNING', 'STOPPING')
                         ORDER BY CASE j.state WHEN 'RUNNING' THEN 0 WHEN 'STOPPING' THEN 1 ELSE 2 END
                         LIMIT 1) AS active_state
                  FROM processes p
                 WHERE p.tenant_id = ?%s
                 ORDER BY p.name, p.created_at
                """.formatted(saring), args.toArray());
    }

    /** Ada proses bernama itu di folder mana pun. */
    public boolean adaProses(UUID tenantId, String nama) {
        return db.exists("SELECT count(*) FROM processes WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    public boolean adaProses(UUID tenantId, String nama, UUID folderId) {
        return db.exists("SELECT count(*) FROM processes WHERE tenant_id = ? AND folder_id = ? AND name = ?",
                tenantId, folderId, nama);
    }

    /**
     * Folder-folder tempat proses bernama itu tinggal: folder bawaan lebih
     * dulu, lalu yang paling lama — urutan yang sama dengan pemicu basis data
     * {@code isi_folder_dari_proses} di V5.
     */
    public List<Tempat> tempatProses(UUID tenantId, String nama) {
        return db.jdbc().query("""
                SELECT p.folder_id, f.is_default
                  FROM processes p
                  JOIN folders f ON f.id = p.folder_id
                 WHERE p.tenant_id = ? AND p.name = ?
                 ORDER BY f.is_default DESC, p.created_at
                """, (rs, i) -> new Tempat(rs.getObject(1, UUID.class), rs.getBoolean(2)), tenantId, nama);
    }

    /** COALESCE: medan yang tidak dikirim tidak menghapus nilai yang sudah ada. */
    public void perbaruiProses(UUID tenantId, UUID folderId, String nama, String paket, String versi,
                               String lingkungan, String keterangan) {
        db.exec("""
                UPDATE processes
                   SET package_name = COALESCE(?, package_name),
                       package_version = COALESCE(?, package_version),
                       environment = COALESCE(?, environment),
                       description = COALESCE(?, description)
                 WHERE tenant_id = ? AND folder_id = ? AND name = ?
                """, paket, versi, lingkungan, keterangan, tenantId, folderId, nama);
    }

    /**
     * @param folderId null berarti folder bawaan — diisi oleh pemicu basis data
     *                 {@code trg_processes_folder}, bukan di sini.
     */
    public void buatProses(UUID tenantId, String nama, String paket, String versi,
                           String lingkungan, String keterangan, UUID folderId) {
        db.exec("""
                INSERT INTO processes
                    (id, tenant_id, name, package_name, package_version, environment, description,
                     folder_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                """, Db.newId(), tenantId, nama, paket, versi, lingkungan, keterangan, folderId);
    }

    /**
     * Nama pemicu proses ini di folder asal yang SUDAH ADA di folder tujuan,
     * atau null. Pemicu ikut pindah bersama prosesnya, dan nama pemicu unik
     * per folder.
     */
    public String pemicuBentrok(UUID tenantId, String nama, UUID dari, UUID ke) {
        Object bentrok = db.scalar("""
                SELECT t.name FROM triggers t
                 WHERE t.tenant_id = ? AND t.folder_id = ? AND t.process_name = ?
                   AND EXISTS (SELECT 1 FROM triggers u
                                WHERE u.tenant_id = t.tenant_id AND u.folder_id = ? AND u.name = t.name)
                 ORDER BY t.name
                 LIMIT 1
                """, tenantId, dari, nama, ke);

        return bentrok == null ? null : String.valueOf(bentrok);
    }

    /**
     * Pindahkan proses ke folder lain, BERSAMA pemicu dan riwayat pekerjaannya.
     *
     * <p>Pemicu yang tertinggal di folder lama akan menjalankan proses yang
     * sudah tidak terlihat di sana, dan riwayat yang tertinggal membuat proses
     * di folder baru tampak belum pernah dijalankan. Yang ikut hanya milik
     * folder ASAL: proses bernama sama di folder lain tidak disentuh.
     */
    public int pindahProses(UUID tenantId, String nama, UUID dari, UUID ke) {
        int diubah = db.exec("UPDATE processes SET folder_id = ? WHERE tenant_id = ? AND folder_id = ? AND name = ?",
                ke, tenantId, dari, nama);

        if (diubah > 0) {
            db.exec("UPDATE triggers SET folder_id = ? WHERE tenant_id = ? AND folder_id = ? AND process_name = ?",
                    ke, tenantId, dari, nama);
            db.exec("UPDATE jobs SET folder_id = ? WHERE tenant_id = ? AND folder_id = ? AND process_name = ?",
                    ke, tenantId, dari, nama);
        }

        return diubah;
    }

    /**
     * Dipakai penerbitan paket: menyetel paket dan versinya tanpa menyentuh
     * sisanya — di SEMUA folder tempat proses bernama itu dipasang. Studio
     * tidak tahu folder, dan "terbitkan" selama ini berarti "proses bernama
     * ini menjalankan versi yang baru".
     */
    public void tautkanPaket(UUID tenantId, String nama, String paket, String versi) {
        db.exec("""
                UPDATE processes SET package_name = ?, package_version = ?
                 WHERE tenant_id = ? AND name = ?
                """, paket, versi, tenantId, nama);
    }

    public int hapusProses(UUID tenantId, String nama, UUID folderId) {
        return db.exec("DELETE FROM processes WHERE tenant_id = ? AND folder_id = ? AND name = ?",
                tenantId, folderId, nama);
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
    public List<Map<String, Object>> paket(UUID tenantId, UUID folderId) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String saring = "";

        // Paket tidak tinggal di folder: umpannya satu untuk seluruh penyewa,
        // seperti umpan penyewa di UiPath. Di dalam folder, yang ditampilkan
        // adalah paket yang DIPAKAI proses di folder itu.
        if (folderId != null) {
            saring = " AND name IN (SELECT package_name FROM processes WHERE tenant_id = ? AND folder_id = ?)";
            args.add(tenantId);
            args.add(folderId);
        }

        return db.rows("""
                SELECT id, name, version, description, entry_point, published_by, published_at, size_bytes
                  FROM packages
                 WHERE tenant_id = ?%s
                 ORDER BY name, published_at DESC
                """.formatted(saring), args.toArray());
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
