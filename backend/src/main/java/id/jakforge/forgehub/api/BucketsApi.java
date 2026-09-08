package id.jakforge.forgehub.api;

import id.jakforge.forgehub.data.Db;
import id.jakforge.forgehub.security.CurrentUser;
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
 * Gudang berkas: masukan, keluaran, dan lampiran yang dipakai bersama proses.
 */
@RestController
@RequestMapping("/api/buckets")
public class BucketsApi {

    /**
     * Batas ukuran satu berkas.
     *
     * <p>Sama alasannya dengan batas paket: isinya dikirim sebagai base64 di
     * dalam badan JSON, jadi berkas 32 MB menjadi sekitar 43 MB teks yang harus
     * muat di memori sekaligus.
     */
    private static final long MAKS_BITA = 32L * 1024 * 1024;

    private final Db db;

    public BucketsApi(Db db) {
        this.db = db;
    }

    @GetMapping
    public List<Map<String, Object>> daftar() {
        return db.rows("""
                SELECT b.id, b.name, b.description, b.created_at,
                       count(f.id)                          AS file_count,
                       COALESCE(sum(f.size_bytes), 0)       AS total_bytes
                  FROM buckets b
                  LEFT JOIN bucket_files f
                         ON f.tenant_id = b.tenant_id AND f.bucket_name = b.name
                 WHERE b.tenant_id = ?
                 GROUP BY b.id, b.name, b.description, b.created_at
                 ORDER BY b.name
                """, CurrentUser.get().tenantId());
    }

    @PostMapping
    public ResponseEntity<?> buat(@RequestBody(required = false) Map<String, Object> body) {
        String name = Badan.nama(body, "name");

        if (name == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nama gudang wajib diisi."));
        }

        UUID tenantId = CurrentUser.get().tenantId();

        if (db.exists("SELECT count(*) FROM buckets WHERE tenant_id = ? AND name = ?", tenantId, name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Gudang '" + name + "' sudah ada."));
        }

        db.exec("""
                INSERT INTO buckets (id, tenant_id, name, description, created_at)
                VALUES (?, ?, ?, ?, now())
                """, Db.newId(), tenantId, name, Badan.teks(body, "description"));

        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{name}")
    @Transactional
    public ResponseEntity<?> hapus(@PathVariable String name) {
        UUID tenantId = CurrentUser.get().tenantId();

        // Berkasnya ikut dihapus: berkas yang menggantung tanpa gudang induk
        // tidak bisa dilihat lagi lewat jalan mana pun, tapi tetap memakai
        // tempat di basis data.
        db.exec("DELETE FROM bucket_files WHERE tenant_id = ? AND bucket_name = ?", tenantId, name);

        int terhapus = db.exec("DELETE FROM buckets WHERE tenant_id = ? AND name = ?", tenantId, name);

        return terhapus == 0
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Gudang tidak ada."))
                : ResponseEntity.ok(Map.of("ok", true));
    }

    // -----------------------------------------------------------------
    // Berkas
    // -----------------------------------------------------------------

    @GetMapping("/{name}/files")
    public List<Map<String, Object>> berkas(@PathVariable String name) {
        return db.rows("""
                SELECT id, file_name, content_type, size_bytes, uploaded_by, uploaded_at
                  FROM bucket_files
                 WHERE tenant_id = ? AND bucket_name = ?
                 ORDER BY uploaded_at DESC
                """, CurrentUser.get().tenantId(), name);
    }

    @PostMapping("/{name}/files")
    @Transactional
    public ResponseEntity<?> unggah(@PathVariable String name,
                                    @RequestBody(required = false) Map<String, Object> body) {

        String namaBerkas = bersihkanNama(Badan.nama(body, "fileName"));

        if (namaBerkas == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "fileName wajib diisi dan sah."));
        }

        byte[] isi;
        try {
            String b64 = Badan.teks(body, "contentBase64", "");
            isi = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "contentBase64 bukan base64 yang sah."));
        }

        if (isi.length > MAKS_BITA) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Berkas terlalu besar. Batasnya " + (MAKS_BITA / 1024 / 1024) + " MB."));
        }

        UUID tenantId = CurrentUser.get().tenantId();

        if (!db.exists("SELECT count(*) FROM buckets WHERE tenant_id = ? AND name = ?", tenantId, name)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Gudang '" + name + "' tidak ada."));
        }

        // Berkas dengan nama yang sama DIGANTI, bukan ditumpuk. Gudang berisi
        // lima "laporan.xlsx" dengan waktu unggah berbeda tidak menolong siapa
        // pun yang mencari laporan hari ini.
        db.exec("""
                DELETE FROM bucket_files
                 WHERE tenant_id = ? AND bucket_name = ? AND file_name = ?
                """, tenantId, name, namaBerkas);

        db.exec("""
                INSERT INTO bucket_files
                    (id, tenant_id, bucket_name, file_name, content_type, size_bytes,
                     uploaded_by, uploaded_at, content)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?)
                """, Db.newId(), tenantId, name, namaBerkas,
                Badan.teks(body, "contentType", "application/octet-stream"),
                (long) isi.length, CurrentUser.get().username(), isi);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("fileName", namaBerkas);
        hasil.put("sizeBytes", isi.length);

        return ResponseEntity.ok(hasil);
    }

    @GetMapping("/{name}/files/{id}/content")
    public ResponseEntity<?> isiBerkas(@PathVariable String name, @PathVariable String id) {
        UUID berkasId = Db.uuid(id);
        if (berkasId == null) return berkasTidakAda();

        List<Map<String, Object>> baris = db.jdbc().query("""
                SELECT file_name, content_type, content FROM bucket_files
                 WHERE tenant_id = ? AND bucket_name = ? AND id = ?
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("fileName", rs.getString(1));
                    m.put("contentType", rs.getString(2));
                    m.put("content", rs.getBytes(3));
                    return m;
                }, CurrentUser.get().tenantId(), name, berkasId);

        if (baris.isEmpty() || baris.get(0).get("content") == null) return berkasTidakAda();

        Map<String, Object> f = baris.get(0);
        Object tipe = f.get("contentType");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        tipe == null ? "application/octet-stream" : tipe.toString()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(String.valueOf(f.get("fileName")))
                                .build().toString())
                .body((byte[]) f.get("content"));
    }

    @DeleteMapping("/{name}/files/{id}")
    public ResponseEntity<?> hapusBerkas(@PathVariable String name, @PathVariable String id) {
        UUID berkasId = Db.uuid(id);
        if (berkasId == null) return berkasTidakAda();

        int terhapus = db.exec("""
                DELETE FROM bucket_files WHERE tenant_id = ? AND bucket_name = ? AND id = ?
                """, CurrentUser.get().tenantId(), name, berkasId);

        return terhapus == 0 ? berkasTidakAda() : ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Buang segala yang bisa mengubah tempat berkas mendarat.
     *
     * <p>Nama berkas datang dari luar dan dipakai sebagai nama unduhan. Pemisah
     * jalur dibuang supaya tidak ada yang bisa menulis {@code ../} ke dalamnya
     * dan menaruh berkas di tempat lain pada komputer orang yang mengunduhnya.
     */
    private static String bersihkanNama(String nama) {
        if (nama == null) return null;

        String bersih = nama.replace('\\', '/');
        int garis = bersih.lastIndexOf('/');
        if (garis >= 0) bersih = bersih.substring(garis + 1);

        // "." dan ".." tidak menyisakan apa pun sesudah pemisahnya dibuang, dan
        // keduanya bukan nama berkas.
        bersih = bersih.trim();
        if (bersih.isEmpty() || ".".equals(bersih) || "..".equals(bersih)) return null;

        return bersih;
    }

    private static ResponseEntity<?> berkasTidakAda() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Berkas tidak ada."));
    }
}
