package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.common.RequestBodies;
import id.jakforge.openorchestrator.dto.request.AssistantCodeRequest;
import id.jakforge.openorchestrator.dto.request.AssistantTokenRequest;
import id.jakforge.openorchestrator.dto.response.AssistantCodeResponse;
import id.jakforge.openorchestrator.dto.response.AssistantTokenResponse;
import id.jakforge.openorchestrator.dto.response.OkResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.AssistantSignInService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Open Assistant masuk lewat dasbor (ROBOT-API.md bagian 5).
 *
 * <p>{@code /code} dipanggil halaman dasbor {@code /assistant/connect} oleh orang
 * yang sudah masuk; {@code /token}, {@code /refresh}, dan {@code /logout}
 * dipanggil Open Assistant TANPA token — yang membuktikan siapa dia adalah kode
 * beserta code_verifier-nya, atau refresh token-nya.
 *
 * <p>Jawaban berisi token tidak boleh tersimpan di cache mana pun.
 */
@RestController
@RequestMapping("/api/auth/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private static final String REFRESH_TOKEN_FIELD = "refreshToken";

    private final AssistantSignInService assistantSignInService;

    @PostMapping("/code")
    public ResponseEntity<AssistantCodeResponse> createCode(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                                            @RequestBody AssistantCodeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assistantSignInService.createCode(principal, request));
    }

    @PostMapping("/token")
    public ResponseEntity<AssistantTokenResponse> exchange(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assistantSignInService.exchange(AssistantTokenRequest.fromBody(body)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AssistantTokenResponse> refresh(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assistantSignInService.refresh(RequestBodies.trimmedText(body, REFRESH_TOKEN_FIELD)));
    }

    @PostMapping("/logout")
    public OkResponse logout(@RequestBody(required = false) Map<String, Object> body) {
        return assistantSignInService.logout(RequestBodies.trimmedText(body, REFRESH_TOKEN_FIELD));
    }

    /** Open Assistant yang tersambung atas nama orang yang sedang masuk. */
    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return assistantSignInService.sessions(principal);
    }

    @DeleteMapping("/sessions/{id}")
    public OkResponse revoke(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String id) {
        return assistantSignInService.revoke(principal, id);
    }
}
