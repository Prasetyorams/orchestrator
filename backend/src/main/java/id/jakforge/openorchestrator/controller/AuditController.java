package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Jejak audit, hanya untuk dibaca — oleh peran yang punya izin audit.read,
 * yang diperiksa PermissionInterceptor sebelum permintaannya sampai di sini.
 *
 * <p>Tidak ada jalur untuk menghapus atau mengubahnya, dengan sengaja: jejak
 * yang bisa dibersihkan oleh orang yang jejaknya tercatat di sana bukan lagi
 * jejak.
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping
    public List<Map<String, Object>> findRecent(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                                @RequestParam(required = false) String component,
                                                @RequestParam(name = "q", required = false) String keyword,
                                                @RequestParam(required = false) Integer limit) {
        return auditService.findRecent(principal, component, keyword, limit);
    }

    /** Komponen yang pernah tercatat, beserta jumlahnya — untuk pilihan penyaring. */
    @GetMapping("/components")
    public List<Map<String, Object>> countByComponent(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return auditService.countByComponent(principal);
    }
}
