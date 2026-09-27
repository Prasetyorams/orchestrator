package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.response.SettingsResponse;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Halaman Setelan: waktu server, zona tampilan, umur token, dan isi basis data. */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final TenantService tenantService;

    @GetMapping
    public SettingsResponse getSettings(@AuthenticationPrincipal ForgeHubPrincipal principal) {
        return tenantService.getSettings(principal);
    }
}
