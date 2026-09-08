package id.jakforge.forgehub.api;

import id.jakforge.forgehub.data.Db;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.security.SecretBox;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aset dan kredensial.
 *
 * <p>Keduanya menyimpan nilai yang harus bisa DIBACA KEMBALI oleh robot, bukan
 * sekadar diringkas: robot perlu kata sandi aslinya untuk masuk ke aplikasi
 * yang diotomasi. Karena itu yang dipakai adalah penyandian dua arah
 * ({@link SecretBox}), bukan hash.
 *
 * <p>Nilai rahasia dibuka HANYA di endpoint {@code /value}. Daftar biasa
 * mengembalikan penanda "ada isinya", bukan isinya — supaya membuka halaman
 * Assets tidak menaburkan seluruh kata sandi ke dalam log peramban, riwayat
 * proksi, dan tangkapan layar.
 */
@RestController
@RequestMapping("/api")
public class AssetsApi {

    private static final Set<String> TIPE_SAH = Set.of("Text", "Integer", "Bool", "Credential", "Secret");
    private static final Set<String> TIPE_RAHASIA = Set.of("Credential", "Secret");

    private final Db db;
    private final SecretBox secrets;

    public AssetsApi(Db db, SecretBox secrets) {
        this.db = db;
        this.secrets = secrets;
    }

    // -----------------------------------------------------------------
    // Aset
    // -----------------------------------------------------------------

    @GetMapping("/assets")
    public List<Map<String, Object>> aset() {
        return db.rows("""
                SELECT id, name, type, scope, description, created_at, updated_at,
                       CASE WHEN type IN ('Credential', 'Secret') THEN NULL ELSE value_text END AS value_text,
                       CASE WHEN value_text IS NULL OR value_text = '' THEN FALSE ELSE TRUE END AS has_value
                  FROM assets
                 WHERE tenant_id = ?
                 ORDER BY name
                """, CurrentUser.get().tenantId());
    }

    @PostMapping("/assets")
    public ResponseEntity<?> simpanAset(@RequestBody(required = false) Map<String, Object> body) {
        String name = Badan.nama(body, "name");

        if (name == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nama aset wajib diisi."));
        }

        String tipe = Badan.teks(body, "type", "Text");
        String nilai = Badan.teks(body, "value");

        if (!TIPE_SAH.contains(tipe)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Tipe aset tidak dikenal: '" + tipe + "'."));
        }

        // Nilai bertipe angka dan boolean diperiksa DI SINI, bukan dibiarkan
        // meledak nanti di dalam robot yang sedang berjalan. Kegagalan di sini
        // dilihat orang yang baru saja mengetiknya; kegagalan di sana dilihat
        // sebagai automasi yang berhenti di tengah malam.
        if ("Integer".equals(tipe) && nilai != null && !nilai.isEmpty()) {
            try {
                Long.parseLong(nilai.trim());
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Aset bertipe Integer harus berisi bilangan bulat."));
            }
        }

        if ("Bool".equals(tipe) && nilai != null && !nilai.isEmpty()) {
            String v = nilai.trim();
            if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Aset bertipe Bool harus berisi true atau false."));
            }
        }

        String tersimpan = TIPE_RAHASIA.contains(tipe) ? secrets.protect(nilai) : nilai;

        UUID tenantId = CurrentUser.get().tenantId();

        Object adaId = db.scalar("SELECT id FROM assets WHERE tenant_id = ? AND name = ?", tenantId, name);

        if (adaId != null) {
            db.exec("""
                    UPDATE assets
                       SET type = ?, value_text = ?, description = ?, scope = ?, updated_at = now()
                     WHERE tenant_id = ? AND name = ?
                    """, tipe, tersimpan, Badan.teks(body, "description"),
                    Badan.teks(body, "scope", "Global"), tenantId, name);
        } else {
            db.exec("""
                    INSERT INTO assets (id, tenant_id, name, type, value_text, description, scope,
                                        created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())
                    """, Db.newId(), tenantId, name, tipe, tersimpan,
                    Badan.teks(body, "description"), Badan.teks(body, "scope", "Global"));
        }

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("created", adaId == null);

        return ResponseEntity.ok(hasil);
    }

    /** Pengambilan nilai untuk robot. Aset rahasia dibuka di sini SAJA. */
    @GetMapping("/assets/{name}/value")
    public ResponseEntity<?> nilaiAset(@PathVariable String name) {
        Map<String, Object> row = db.row(
                "SELECT type, value_text FROM assets WHERE tenant_id = ? AND name = ?",
                CurrentUser.get().tenantId(), name);

        if (row == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Aset '" + name + "' tidak ada."));
        }

        String tipe = (String) row.get("type");
        String mentah = (String) row.get("valueText");
        String nilai = TIPE_RAHASIA.contains(tipe) ? secrets.unprotect(mentah) : mentah;

        // HashMap, bukan Map.of: nilainya boleh null, dan Map.of melempar
        // NullPointerException untuk nilai null. Aset yang ada tapi kosong
        // adalah keadaan yang sah.
        Map<String, Object> hasil = new HashMap<>();
        hasil.put("name", name);
        hasil.put("type", tipe);
        hasil.put("value", nilai);

        return ResponseEntity.ok(hasil);
    }

    @DeleteMapping("/assets/{name}")
    public ResponseEntity<?> hapusAset(@PathVariable String name) {
        int terhapus = db.exec("DELETE FROM assets WHERE tenant_id = ? AND name = ?",
                CurrentUser.get().tenantId(), name);

        return terhapus == 0
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Aset tidak ada."))
                : ResponseEntity.ok(Map.of("ok", true));
    }

    // -----------------------------------------------------------------
    // Kredensial
    // -----------------------------------------------------------------

    /** Daftar kredensial TANPA kata sandinya — kolomnya tidak ikut dipilih. */
    @GetMapping("/credentials")
    public List<Map<String, Object>> kredensial() {
        return db.rows("""
                SELECT id, name, username, description, created_at
                  FROM credentials
                 WHERE tenant_id = ?
                 ORDER BY name
                """, CurrentUser.get().tenantId());
    }

    @PostMapping("/credentials")
    public ResponseEntity<?> simpanKredensial(@RequestBody(required = false) Map<String, Object> body) {
        String name = Badan.nama(body, "name");

        if (name == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nama kredensial wajib diisi."));
        }

        UUID tenantId = CurrentUser.get().tenantId();
        String tersandi = secrets.protect(Badan.teks(body, "password"));

        if (db.exists("SELECT count(*) FROM credentials WHERE tenant_id = ? AND name = ?", tenantId, name)) {
            // COALESCE pada password_enc: menyunting keterangan tanpa mengisi
            // ulang kata sandinya tidak boleh MENGHAPUS kata sandi yang ada.
            // Ini beda dari sisi .NET, yang menimpanya dengan null — dan
            // akibatnya baru terlihat saat robot berikutnya gagal masuk.
            db.exec("""
                    UPDATE credentials
                       SET username = ?, password_enc = COALESCE(?, password_enc), description = ?
                     WHERE tenant_id = ? AND name = ?
                    """, Badan.teks(body, "username"), tersandi,
                    Badan.teks(body, "description"), tenantId, name);
        } else {
            db.exec("""
                    INSERT INTO credentials (id, tenant_id, name, username, password_enc, description, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, now())
                    """, Db.newId(), tenantId, name, Badan.teks(body, "username"),
                    tersandi, Badan.teks(body, "description"));
        }

        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/credentials/{name}/value")
    public ResponseEntity<?> nilaiKredensial(@PathVariable String name) {
        Map<String, Object> row = db.row(
                "SELECT username, password_enc FROM credentials WHERE tenant_id = ? AND name = ?",
                CurrentUser.get().tenantId(), name);

        if (row == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Kredensial tidak ada."));
        }

        Map<String, Object> hasil = new HashMap<>();
        hasil.put("username", row.get("username"));
        hasil.put("password", secrets.unprotect((String) row.get("passwordEnc")));

        return ResponseEntity.ok(hasil);
    }

    @DeleteMapping("/credentials/{name}")
    public ResponseEntity<?> hapusKredensial(@PathVariable String name) {
        int terhapus = db.exec("DELETE FROM credentials WHERE tenant_id = ? AND name = ?",
                CurrentUser.get().tenantId(), name);

        return terhapus == 0
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Kredensial tidak ada."))
                : ResponseEntity.ok(Map.of("ok", true));
    }
}
