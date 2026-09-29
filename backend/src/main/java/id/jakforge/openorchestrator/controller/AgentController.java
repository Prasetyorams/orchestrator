package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.common.RequestBodies;
import id.jakforge.openorchestrator.dto.request.AgentHeartbeatRequest;
import id.jakforge.openorchestrator.dto.request.AgentLoginRequest;
import id.jakforge.openorchestrator.dto.request.AgentStateReport;
import id.jakforge.openorchestrator.dto.request.LogBatchRequest;
import id.jakforge.openorchestrator.dto.response.AgentHeartbeatResponse;
import id.jakforge.openorchestrator.dto.response.AgentLoginResponse;
import id.jakforge.openorchestrator.dto.response.AgentStateResponse;
import id.jakforge.openorchestrator.dto.response.LogWriteResponse;
import id.jakforge.openorchestrator.dto.response.WindowsCredentialResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.AgentAuthService;
import id.jakforge.openorchestrator.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Robot Agent unattended — kontrak v2 (ROBOT-API.md bagian 2).
 *
 * <p>Badan permintaan dibaca longgar seperti endpoint robot v1: agent yang
 * lebih baru boleh mengirim medan yang belum dikenal di sini.
 *
 * <p>Semua endpoint kecuali login hanya menerima token agent; yang memeriksanya
 * PermissionInterceptor (Access.AGENT), termasuk apakah machine key-nya masih
 * berlaku.
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentAuthService agentAuthService;
    private final AgentService agentService;

    /** Tukar machine key dengan token satu jam, beserta robot dan setelan mesinnya. */
    @PostMapping("/login")
    public ResponseEntity<AgentLoginResponse> login(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(agentAuthService.login(AgentLoginRequest.fromBody(body)));
    }

    @PostMapping("/heartbeat")
    public AgentHeartbeatResponse heartbeat(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                            @RequestBody(required = false) Map<String, Object> body) {
        return agentService.heartbeat(principal, AgentHeartbeatRequest.fromBody(body));
    }

    /** 200 {@code {"job": {...}}}, atau 204 kalau tidak ada job untuk robot itu sekarang. */
    @PostMapping("/jobs/claim")
    public ResponseEntity<Map<String, Object>> claim(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        return agentService.claim(principal, RequestBodies.trimmedText(body, "robotId"))
                .map(job -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.<String, Object>of("job", job)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/jobs/{id}/state")
    public AgentStateResponse reportState(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                          @PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        return agentService.reportState(principal, id, AgentStateReport.fromBody(body));
    }

    /** POST, bukan GET: jawaban berisi sandi tidak boleh tersimpan di cache atau log proxy. */
    @PostMapping("/jobs/{id}/windows-credential")
    public ResponseEntity<WindowsCredentialResponse> windowsCredential(
            @AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(agentService.windowsCredential(principal, id));
    }

    @PostMapping("/logs")
    public LogWriteResponse writeLogs(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                      @RequestBody(required = false) Map<String, Object> body) {
        return agentService.writeLogs(principal, LogBatchRequest.fromBody(body));
    }

    @PostMapping(value = "/jobs/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> addAttachment(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                             @PathVariable String id,
                                             @RequestParam("file") MultipartFile file,
                                             @RequestParam(value = "kind", required = false) String kind)
            throws IOException {
        return agentService.addAttachment(principal, id, kind, file.getOriginalFilename(), file.getBytes());
    }
}
