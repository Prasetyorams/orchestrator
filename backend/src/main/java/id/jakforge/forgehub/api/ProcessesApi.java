package id.jakforge.forgehub.api;

import id.jakforge.forgehub.data.Db;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Proses dan paket.
 *
 * <p>Studio memanggil {@code GET /api/packages} untuk menghitung nomor versi
 * berikutnya, {@code POST /api/packages} untuk menerbitkan, dan
 * {@code GET /api/processes} untuk daftar pilihan. JakRunner mengunduh isinya
 * lewat {@code /content}.
 */
@RestController
@RequestMapping("/api")
public class ProcessesApi {

    /**
     * Batas ukuran paket.
     *
     * <p>Ada karena isinya dikirim sebagai base64 DI DALAM badan JSON: paket 64
     * MB menjadi sekitar 85 MB teks yang harus muat di memori sekaligus. Tanpa
     * batas, satu penerbitan yang keliru menjatuhkan layanan untuk semua orang.
     */
    private static final long MAKS_PAKET_BITA = 64L * 1024 * 1024;

    private final Db db;

    public ProcessesApi(Db db) {
        this.db = db;
    }

    // -----------------------------------------------------------------
    // Proses
    // -----------------------------------------------------------------

    @GetMapping("/processes")
    public List<Map<String, Object>> proses() {
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
                """, CurrentUser.get().tenantId());
    }

    /**
     * Nama yang sudah ada DIPERBARUI, bukan ditolak.
     *
     * <p>Menerbitkan ulang versi yang lebih baru adalah hal yang paling sering
     * dilakukan, dan menolaknya sebagai "sudah ada" memaksa orang menghapus
     * dulu — yang berarti sesaat prosesnya tidak ada sama sekali.
     */
    @PostMapping("/processes")
    public ResponseEntity<?> buatProses(@RequestBody(required = false) Map<String, Object> body) {
        String name = Badan.nama(body, "name");

        if (name == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nama proses wajib diisi."));
        }

        UUID tenantId = CurrentUser.get().tenantId();

        Object adaId = db.scalar(
                "SELECT id FROM processes WHERE tenant_id = ? AND name = ?", tenantId, name);

        if (adaId != null) {
            db.exec("""
                    UPDATE processes
                       SET package_name = COALESCE(?, package_name),
                           package_version = COALESCE(?, package_version),
                           environment = COALESCE(?, environment),
                           description = COALESCE(?, description)
                     WHERE tenant_id = ? AND name = ?
                    """, Badan.teks(body, "packageName"), Badan.teks(body, "packageVersion"),
                    Badan.teks(body, "environment"), Badan.teks(body, "description"), tenantId, name);
        } else {
            db.exec("""
                    INSERT INTO processes
                        (id, tenant_id, name, package_name, package_version, environment, description, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, now())
                    """, Db.newId(), tenantId, name,
                    Badan.teks(body, "packageName"), Badan.teks(body, "packageVersion"),
                    Badan.teks(body, "environment", "Production"), Badan.teks(body, "description"));
        }

        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/processes/{name}")
    public ResponseEntity<?> hapusProses(@PathVariable String name) {
        int terhapus = db.exec("DELETE FROM processes WHERE tenant_id = ? AND name = ?",
                CurrentUser.get().tenantId(), name);

        return terhapus == 0
                ? ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Proses '" + name + "' tidak ada."))
                : ResponseEntity.ok(Map.of("ok", true));
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
    @GetMapping("/packages")
    public List<Map<String, Object>> paket() {
        return db.rows("""
                SELECT id, name, version, description, entry_point, published_by, published_at, size_bytes
                  FROM packages
                 WHERE tenant_id = ?
                 ORDER BY name, published_at DESC
                """, CurrentUser.get().tenantId());
    }

    /**
     * Penerbitan dari Studio.
     *
     * <p>Isi paket dikirim sebagai base64 di dalam JSON, bukan multipart.
     * Penerbitnya adalah Studio di .NET Framework 4.6.2 yang menyusun
     * permintaan multipart dengan tangan; base64 lebih besar 33% tapi hanya
     * butuh satu jalan yang sama dengan permintaan lainnya.
     */
    @PostMapping("/packages")
    @Transactional
    public ResponseEntity<?> terbitkan(@RequestBody(required = false) Map<String, Object> body) {
        String name = Badan.nama(body, "name");
        String version = Badan.teks(body, "version", "1.0.0");

        if (name == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nama paket wajib diisi."));
        }

        byte[] isi = null;
        String base64 = Badan.teks(body, "contentBase64");

        if (base64 != null && !base64.isEmpty()) {
            try {
                isi = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "contentBase64 bukan base64 yang sah."));
            }

            if (isi.length > MAKS_PAKET_BITA) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Paket terlalu besar. Batasnya " + (MAKS_PAKET_BITA / 1024 / 1024) + " MB."));
            }
        }

        ForgeHubPrincipal p = CurrentUser.get();
        UUID tenantId = p.tenantId();
        String penerbit = p.username();
        long ukuran = isi == null ? 0 : isi.length;

        Object adaId = db.scalar("""
                SELECT id FROM packages WHERE tenant_id = ? AND name = ? AND version = ?
                """, tenantId, name, version);

        if (adaId != null) {
            // COALESCE pada content: penerbitan ulang yang hanya memperbarui
            // keterangan tidak boleh MENGHAPUS isi paket yang sudah ada.
            db.exec("""
                    UPDATE packages
                       SET description = ?, entry_point = ?, published_by = ?,
                           published_at = now(), size_bytes = ?,
                           content = COALESCE(?, content)
                     WHERE tenant_id = ? AND name = ? AND version = ?
                    """, Badan.teks(body, "description"), Badan.teks(body, "entryPoint"),
                    penerbit, ukuran, isi, tenantId, name, version);
        } else {
            db.exec("""
                    INSERT INTO packages
                        (id, tenant_id, name, version, description, entry_point,
                         published_by, published_at, size_bytes, content)
                    VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, ?)
                    """, Db.newId(), tenantId, name, version, Badan.teks(body, "description"),
                    Badan.teks(body, "entryPoint"), penerbit, ukuran, isi);
        }

        // Menerbitkan paket hampir selalu berarti ingin proses dengan nama yang
        // sama tersedia untuk dijalankan. Membuatnya di sini menghemat satu
        // langkah yang mudah terlupa — dan yang terlupa itu baru terasa saat
        // pekerjaan ditolak dengan "proses belum diterbitkan".
        boolean prosesAda = db.exists(
                "SELECT count(*) FROM processes WHERE tenant_id = ? AND name = ?", tenantId, name);

        if (prosesAda) {
            db.exec("""
                    UPDATE processes SET package_name = ?, package_version = ?
                     WHERE tenant_id = ? AND name = ?
                    """, name, version, tenantId, name);
        } else {
            db.exec("""
                    INSERT INTO processes
                        (id, tenant_id, name, package_name, package_version, environment, description, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, now())
                    """, Db.newId(), tenantId, name, name, version,
                    Badan.teks(body, "environment", "Production"), Badan.teks(body, "description"));
        }

        Peringatan.catat(db, tenantId, "Info", "Paket diterbitkan",
                name + " " + version + " diterbitkan oleh " + (penerbit == null ? "?" : penerbit) + ".",
                "packages");

        db.exec("""
                INSERT INTO logs (tenant_id, level, message, process_name, logged_at)
                VALUES (?, 'INFO', ?, ?, now())
                """, tenantId, "Paket " + name + " " + version + " diterbitkan.", name);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("name", name);
        hasil.put("version", version);
        hasil.put("sizeBytes", ukuran);

        return ResponseEntity.ok(hasil);
    }

    @GetMapping("/packages/{name}/{version}/content")
    public ResponseEntity<?> isiPaket(@PathVariable String name, @PathVariable String version) {
        // Isi diambil lewat kueri tersendiri yang mengembalikan bita mentah.
        // Db.rows sengaja mengubah BYTEA menjadi ukurannya, karena isi paket
        // tidak pernah benar dikirim sebagai bagian dari JSON.
        List<byte[]> isi = db.jdbc().query(
                "SELECT content FROM packages WHERE tenant_id = ? AND name = ? AND version = ?",
                (rs, i) -> rs.getBytes(1),
                CurrentUser.get().tenantId(), name, version);

        if (isi.isEmpty() || isi.get(0) == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Paket tidak ada atau tanpa isi."));
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(name + "." + version + ".zip")
                                .build().toString())
                .body(isi.get(0));
    }

    @DeleteMapping("/packages/{name}/{version}")
    public ResponseEntity<?> hapusPaket(@PathVariable String name, @PathVariable String version) {
        int terhapus = db.exec(
                "DELETE FROM packages WHERE tenant_id = ? AND name = ? AND version = ?",
                CurrentUser.get().tenantId(), name, version);

        return terhapus == 0
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Paket tidak ada."))
                : ResponseEntity.ok(Map.of("ok", true));
    }
}
