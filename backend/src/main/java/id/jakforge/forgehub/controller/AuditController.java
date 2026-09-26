package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.repository.AuditRepository;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.service.Batas;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Jejak audit, hanya untuk dibaca — oleh peran yang punya izin audit.read,
 * yang diperiksa IzinInterceptor sebelum permintaannya sampai di sini.
 *
 * <p>Tidak ada jalur untuk menghapus atau mengubahnya, dengan sengaja: jejak
 * yang bisa dibersihkan oleh orang yang jejaknya tercatat di sana bukan lagi
 * jejak.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditRepository audit;

    public AuditController(AuditRepository audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> daftar(@RequestParam(required = false) String component,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) Integer limit) {
        return audit.daftar(CurrentUser.get().tenantId(), component, q, Batas.antara(limit, 200, 2000));
    }

    /** Komponen yang pernah tercatat, beserta jumlahnya — untuk pilihan penyaring. */
    @GetMapping("/components")
    public List<Map<String, Object>> komponen() {
        return audit.komponen(CurrentUser.get().tenantId());
    }
}
