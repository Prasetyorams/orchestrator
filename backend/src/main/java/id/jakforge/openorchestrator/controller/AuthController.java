package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.dto.request.ChangePasswordRequest;
import id.jakforge.openorchestrator.dto.request.LoginRequest;
import id.jakforge.openorchestrator.dto.request.UpdateProfileRequest;
import id.jakforge.openorchestrator.dto.response.LoginResponse;
import id.jakforge.openorchestrator.dto.response.StatusResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Masuk, siapa saya, ubah profil, dan ganti kata sandi. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** Dibaca longgar: Studio, JakRunner, dan activity Orchestrator masuk lewat sini. */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody(required = false) Map<String, Object> body) {
        return authService.login(LoginRequest.fromBody(body));
    }

    /**
     * Siapa saya, beserta pola izin peran saya — dasbor memakainya untuk tidak
     * menawarkan menu dan tombol yang pasti ditolak. Yang menjaga tetap server.
     */
    @GetMapping("/me")
    public Map<String, Object> getCurrentUser(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return authService.getCurrentUser(principal);
    }

    /** Nama tampilan dan surel milik pengguna yang sedang masuk. */
    @PutMapping("/me")
    public Map<String, Object> updateProfile(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                             @Valid @RequestBody UpdateProfileRequest request) {
        return authService.updateProfile(principal, request);
    }

    @PostMapping("/password")
    public StatusResponse changePassword(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                         @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(principal, request);
    }
}
