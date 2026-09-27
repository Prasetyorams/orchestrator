package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Lisensi robot penyewa. */
@RestController
@RequestMapping("/api/licensing")
@RequiredArgsConstructor
public class LicenseController {

    private final TenantService tenantService;

    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal ForgeHubPrincipal principal) {
        return tenantService.findLicenses(principal);
    }
}
