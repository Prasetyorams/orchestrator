package id.jakforge.forgehub.api;

import id.jakforge.forgehub.data.Db;
import id.jakforge.forgehub.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Peringatan: kejadian yang perlu DILIHAT orang.
 *
 * <p>Terpisah dari log dengan sengaja. Log adalah jejak apa yang terjadi dan
 * jumlahnya ribuan; peringatan muncul sendiri di kepala halaman, jadi jumlahnya
 * harus tetap sedikit supaya masih ada yang membacanya.
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertsApi {

    private final Db db;

    public AlertsApi(Db db) {
        this.db = db;
    }

    @GetMapping
    public List<Map<String, Object>> daftar(
            @RequestParam(required = false) String unread,
            @RequestParam(required = false) Integer limit) {

        // Menerima "1" maupun "true": dasbor .NET mengirim "1", dan klien lain
        // yang menulis "true" tidak boleh diam-diam melihat seluruh daftar.
        boolean hanyaBelumDibaca = "1".equals(unread) || "true".equalsIgnoreCase(unread);

        return db.rows("""
                SELECT id, severity, title, message, source, is_read, created_at
                  FROM alerts
                 WHERE tenant_id = ? AND (NOT ? OR NOT is_read)
                 ORDER BY id DESC
                 LIMIT ?
                """, CurrentUser.get().tenantId(), hanyaBelumDibaca,
                     Badan.batas(limit, 50, 500));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<?> tandaiDibaca(@PathVariable long id) {
        int berubah = db.exec("UPDATE alerts SET is_read = TRUE WHERE tenant_id = ? AND id = ?",
                CurrentUser.get().tenantId(), id);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("changed", berubah);

        return ResponseEntity.ok(hasil);
    }

    @PostMapping("/read-all")
    public ResponseEntity<?> tandaiSemua() {
        int berubah = db.exec(
                "UPDATE alerts SET is_read = TRUE WHERE tenant_id = ? AND NOT is_read",
                CurrentUser.get().tenantId());

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("changed", berubah);

        return ResponseEntity.ok(hasil);
    }
}
