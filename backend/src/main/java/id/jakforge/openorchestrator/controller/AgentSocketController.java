package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.security.JwtService;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.AgentPushService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * WebSocket Robot Agent ({@code GET /api/agent/ws}, kemampuan {@code agent.ws}).
 *
 * <p>Handshake-nya permintaan HTTP biasa, jadi melewati aturan yang sama dengan
 * endpoint agent lain SEBELUM di-upgrade: token agent di header
 * {@code Authorization} (tidak pernah di alamat), dan machine key yang masih
 * berlaku (PermissionInterceptor, Access.AGENT). Yang ditolak mendapat 401/403
 * JSON seperti biasa, bukan sambungan yang langsung ditutup.
 *
 * <p>Upgrade-nya dilakukan di sini lewat {@link ServerContainer#upgradeHttpToWebSocket}
 * (Jakarta WebSocket 2.1, bawaan Tomcat) — tanpa endpoint terdaftar, jadi tidak
 * ada jalur lain ke WebSocket ini yang tidak melewati pemeriksaan itu.
 */
@RestController
@RequiredArgsConstructor
public class AgentSocketController {

    static final String PATH = "/api/agent/ws";
    private static final String BEARER = "Bearer ";

    private final AgentPushService push;
    private final JwtService jwtService;

    @GetMapping(PATH)
    public void connect(@AuthenticationPrincipal OpenOrchestratorPrincipal agent, HttpServletRequest request,
                        HttpServletResponse response) throws IOException {
        if (!"websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
            throw ApiException.badRequest("Endpoint ini hanya untuk sambungan WebSocket (Upgrade: websocket).");
        }

        Object container = request.getServletContext().getAttribute(ServerContainer.class.getName());
        if (!(container instanceof ServerContainer serverContainer)) {
            throw ApiException.unavailable("WebSocket tidak tersedia di server ini.");
        }

        String authorization = request.getHeader("Authorization");
        Instant expiresAt = authorization != null && authorization.startsWith(BEARER)
                ? jwtService.expiresAt(authorization.substring(BEARER.length()).trim())
                : null;

        AgentSocketEndpoint endpoint = new AgentSocketEndpoint(push, agent, expiresAt);
        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(AgentSocketEndpoint.class, PATH)
                .configurator(new ServerEndpointConfig.Configurator() {
                    @Override
                    public <T> T getEndpointInstance(Class<T> endpointClass) {
                        return endpointClass.cast(endpoint);
                    }
                })
                .build();

        try {
            serverContainer.upgradeHttpToWebSocket(request, response, config, Map.of());
        } catch (DeploymentException e) {
            throw ApiException.unavailable("WebSocket tidak bisa dibuka: " + e.getMessage());
        }
    }
}
