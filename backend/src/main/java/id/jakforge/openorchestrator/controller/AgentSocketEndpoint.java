package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.AgentPushService;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;

/**
 * Satu sambungan WebSocket Robot Agent ({@code /api/agent/ws}). Dibuat per
 * sambungan oleh {@link AgentSocketController} sesudah aturan token agent
 * dilewati; isinya diteruskan ke {@link AgentPushService}.
 *
 * <p>Arah agent → server tidak dipakai: laporan keadaan, log, dan klaim tetap
 * HTTP. Pesan teks dari agent dibaca dan diabaikan (agent yang lebih baru
 * boleh mengirim sesuatu); yang terlalu besar menutup sambungan.
 */
@Slf4j
final class AgentSocketEndpoint extends Endpoint {

    /** Batas pesan dari agent — tidak ada yang perlu dikirim agent lewat sini. */
    static final int MAX_INCOMING_MESSAGE = 4096;
    /** Pengiriman ke agent yang macet tidak menahan utas pengirim lebih lama dari ini. */
    static final long SEND_TIMEOUT_MILLIS = 5_000;
    private static final String TOMCAT_BLOCKING_SEND_TIMEOUT = "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT";

    private final AgentPushService push;
    private final OpenOrchestratorPrincipal agent;
    private final Instant expiresAt;
    private SessionConnection connection;

    AgentSocketEndpoint(AgentPushService push, OpenOrchestratorPrincipal agent, Instant expiresAt) {
        this.push = push;
        this.agent = agent;
        this.expiresAt = expiresAt;
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        session.setMaxTextMessageBufferSize(MAX_INCOMING_MESSAGE);
        session.setMaxBinaryMessageBufferSize(MAX_INCOMING_MESSAGE);
        // Tanpa batas diam: ping server yang menjaga sambungan, dan kiriman yang gagal menutupnya.
        session.setMaxIdleTimeout(0);
        session.getUserProperties().put(TOMCAT_BLOCKING_SEND_TIMEOUT, SEND_TIMEOUT_MILLIS);
        session.addMessageHandler(String.class, (MessageHandler.Whole<String>) text -> {
        });

        connection = new SessionConnection(session);
        push.opened(agent, expiresAt, connection);
    }

    @Override
    public void onClose(Session session, CloseReason closeReason) {
        if (connection != null) push.closed(agent.machineId(), connection);
    }

    @Override
    public void onError(Session session, Throwable error) {
        log.debug("WebSocket Robot Agent mesin {}: {}", agent.username(), error.getMessage());
    }

    /** Kiriman ke satu sesi tidak boleh bersamaan: RemoteEndpoint.Basic tidak aman untuk itu. */
    private static final class SessionConnection implements AgentPushService.Connection {

        private final Session session;

        SessionConnection(Session session) {
            this.session = session;
        }

        @Override
        public synchronized void send(String text) throws IOException {
            if (!session.isOpen()) throw new IOException("Sambungan sudah tertutup.");
            session.getBasicRemote().sendText(text);
        }

        @Override
        public synchronized void ping() throws IOException {
            if (!session.isOpen()) throw new IOException("Sambungan sudah tertutup.");
            session.getBasicRemote().sendPing(ByteBuffer.allocate(0));
        }

        @Override
        public void close(int code, String reason) {
            try {
                if (session.isOpen()) session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(code), reason));
            } catch (IOException | IllegalArgumentException e) {
                // Sudah putus.
            }
        }
    }
}
