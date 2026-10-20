package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.dto.request.ChangePasswordRequest;
import id.jakforge.openorchestrator.dto.request.LoginRequest;
import id.jakforge.openorchestrator.dto.request.UpdateProfileRequest;
import id.jakforge.openorchestrator.dto.response.AuthMethodsResponse;
import id.jakforge.openorchestrator.dto.response.LoginResponse;
import id.jakforge.openorchestrator.dto.response.OkResponse;
import id.jakforge.openorchestrator.dto.response.SessionStateResponse;
import id.jakforge.openorchestrator.dto.response.StatusResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.ad.ActiveDirectory;
import id.jakforge.openorchestrator.service.AdLoginService;
import id.jakforge.openorchestrator.service.AuthService;
import id.jakforge.openorchestrator.service.DashboardSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Masuk, keluar, sesi dasbor, siapa saya, ubah profil, dan ganti kata sandi. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final DashboardSessionService dashboardSessionService;
    private final AdLoginService adLoginService;
    private final ActiveDirectory activeDirectory;

    /**
     * Dibaca longgar: Studio, JakRunner, dan activity Orchestrator masuk lewat sini.
     * Dasbor mengirim {@code "client": "dashboard"} (V18).
     */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody(required = false) Map<String, Object> body,
                               @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return authService.login(LoginRequest.fromBody(body), userAgent);
    }

    /**
     * Masuk dengan akun Windows AD (V19): badan yang sama dengan /login
     * (username, password, client). Hanya pengguna yang didaftarkan admin.
     */
    @PostMapping("/login/ad")
    public LoginResponse loginWithActiveDirectory(@RequestBody(required = false) Map<String, Object> body,
                                                  @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return adLoginService.login(LoginRequest.fromBody(body), userAgent);
    }

    /** Cara masuk yang tersedia, untuk layar masuk (tanpa token). */
    @GetMapping("/methods")
    public AuthMethodsResponse methods() {
        return new AuthMethodsResponse(true, new AuthMethodsResponse.Ad(activeDirectory.enabled(), activeDirectory.label()));
    }

    /** Keluar: sesi dasbor dicabut di server, bukan hanya tokennya dibuang dari peramban (V18). */
    @PostMapping("/logout")
    public OkResponse logout(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        dashboardSessionService.logout(principal);
        return OkResponse.success();
    }

    /** Keadaan sesi dan batas waktu menganggurnya, TANPA dihitung sebagai aktivitas (V18). */
    @GetMapping("/session")
    public SessionStateResponse session(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return dashboardSessionService.state(principal);
    }

    /**
     * Dasbor melaporkan aktivitas orang (klik, papan ketik, gulir, sentuh,
     * pindah halaman), juga tombol "Tetap masuk". Sesi yang sudah berakhir
     * dijawab 401, tidak diperpanjang (V18).
     */
    @PostMapping("/session/activity")
    public SessionStateResponse activity(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return dashboardSessionService.recordActivity(principal);
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
