package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.dto.response.SessionTimeoutSettingsResponse;
import id.jakforge.openorchestrator.dto.response.SettingsResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.SessionSettingsService;
import id.jakforge.openorchestrator.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Halaman Setelan: waktu server, zona tampilan, umur token, isi basis data, dan sesi dasbor (V18). */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final TenantService tenantService;
    private final SessionSettingsService sessionSettingsService;

    @GetMapping
    public SettingsResponse getSettings(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return tenantService.getSettings(principal);
    }

    /** Batas waktu menganggur sesi dasbor penyewa ini (settings.read). */
    @GetMapping("/session-timeout")
    public SessionTimeoutSettingsResponse getSessionTimeout(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return sessionSettingsService.get(principal);
    }

    /** Ubah batas waktu menganggur (settings.update); berlaku untuk semua sesi dasbor penyewa ini. */
    @PutMapping("/session-timeout")
    public SessionTimeoutSettingsResponse saveSessionTimeout(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                                             @RequestBody(required = false) Map<String, Object> body) {
        return sessionSettingsService.update(principal, body);
    }
}
