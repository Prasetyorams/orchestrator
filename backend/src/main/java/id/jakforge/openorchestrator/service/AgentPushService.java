package id.jakforge.openorchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.jakforge.openorchestrator.common.Timestamps;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.security.MachineKeys;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Jalur push ke Robot Agent lewat WebSocket ({@code GET /api/agent/ws},
 * kemampuan {@code agent.ws}).
 *
 * <p>Satu sambungan per MESIN. Yang dikirim hanya PETUNJUK:
 * <ul>
 *   <li>{@code jobAvailable} — ada job PENDING yang bisa diambil robot itu; agent mengklaim seperti biasa;</li>
 *   <li>{@code command} — Stop/Kill/Pause/Resume diminta; agent berdenyut sekarang untuk membaca perintahnya;</li>
 *   <li>{@code settingsChanged} — versi setelan mesin berubah; agent masuk lagi untuk mengambilnya.</li>
 * </ul>
 * Klaim, laporan keadaan, log, dan denyut tetap lewat HTTP. Petunjuk yang
 * hilang (sambungan putus, proxy memblokir) tidak menghilangkan apa pun:
 * agent tetap polling seperti tanpa WebSocket, hanya lebih lambat.
 *
 * <p>Petunjuk dikirim SESUDAH transaksinya selesai (job baru sudah terlihat
 * oleh klaim), dari utas sendiri — permintaan dasbor tidak menunggu soket.
 * Aturan token sama dengan endpoint agent lain: sambungan ditutup saat
 * tokennya kedaluwarsa, machine key-nya diganti atau dicabut, atau mesinnya
 * dihapus.
 *
 * <p>Sambungan disimpan di memori proses ini; backend yang dijalankan lebih
 * dari satu instance memerlukan jalur bersama di antaranya.
 */
@Slf4j
@Service
public class AgentPushService {

    /** Kode tutup: sambungan baru dari mesin yang sama menggantikan yang ini. */
    public static final int CLOSE_REPLACED = 4000;
    /** Kode tutup: token kedaluwarsa, machine key diganti/dicabut, atau mesin dihapus — masuk lagi. */
    public static final int CLOSE_UNAUTHORIZED = 4401;
    /** Kode tutup standar: server berhenti. */
    public static final int CLOSE_GOING_AWAY = 1001;

    static final Duration PING_INTERVAL = Duration.ofSeconds(25);

    /** Satu sambungan WebSocket, dipisah dari jakarta.websocket supaya bisa diuji. */
    public interface Connection {
        void send(String text) throws IOException;

        void ping() throws IOException;

        void close(int code, String reason);
    }

    private record Peer(UUID tenantId, UUID machineId, String keyId, Instant expiresAt, Connection connection,
                        int[] settingsVersion) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JobRepository jobRepository;
    private final MachineRepository machineRepository;
    private final Clock clock;
    private final Map<UUID, Peer> peers = new ConcurrentHashMap<>();
    private final ExecutorService sender;
    private volatile Instant nextPing;

    @Autowired
    public AgentPushService(JobRepository jobRepository, MachineRepository machineRepository, Clock clock) {
        this(jobRepository, machineRepository, clock, Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "agent-push");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** @param sender tempat petunjuk dikirim; uji memakai yang langsung menjalankannya */
    AgentPushService(JobRepository jobRepository, MachineRepository machineRepository, Clock clock,
                     ExecutorService sender) {
        this.jobRepository = jobRepository;
        this.machineRepository = machineRepository;
        this.clock = clock;
        this.sender = sender;
        this.nextPing = clock.instant().plus(PING_INTERVAL);
    }

    // ----------------------------------------------------------------- sambungan

    /** Sambungan baru dari agent yang sudah lolos aturan token; yang lama dari mesin yang sama ditutup. */
    public void opened(OpenOrchestratorPrincipal agent, Instant expiresAt, Connection connection) {
        int version = machineRepository.findSettingsVersion(agent.machineId()).orElse(0);
        Peer peer = new Peer(agent.tenantId(), agent.machineId(), agent.keyId(), expiresAt, connection,
                new int[]{version});

        Peer previous = peers.put(agent.machineId(), peer);
        if (previous != null && previous.connection() != connection) {
            previous.connection().close(CLOSE_REPLACED, "Diganti sambungan baru dari mesin yang sama.");
        }

        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("type", "hello");
        hello.put("machineId", agent.machineId().toString());
        hello.put("serverTime", Timestamps.format(clock.instant()));
        hello.put("events", List.of("jobAvailable", "command", "settingsChanged"));
        hello.put("pingSeconds", PING_INTERVAL.toSeconds());
        hello.put("settingsVersion", version);
        // Langsung, di utas sambungannya sendiri: "hello" selalu pesan pertama.
        try {
            connection.send(toJson(hello));
        } catch (IOException | RuntimeException e) {
            drop(peer, CLOSE_GOING_AWAY, "Pengiriman gagal.");
            return;
        }

        log.info("Robot Agent mesin {} tersambung lewat WebSocket.", agent.username());
    }

    /** Sambungan ini selesai; sambungan lain (yang menggantikannya) tidak terpengaruh. */
    public void closed(UUID machineId, Connection connection) {
        peers.computeIfPresent(machineId, (id, peer) -> peer.connection() == connection ? null : peer);
    }

    public int connectedCount() {
        return peers.size();
    }

    public boolean isConnected(UUID machineId) {
        return peers.containsKey(machineId);
    }

    // ----------------------------------------------------------------- petunjuk

    @TransactionalEventListener(fallbackExecution = true)
    public void onJobQueued(AgentPushEvents.JobQueued event) {
        if (peers.isEmpty() || event.jobId() == null) return;

        dispatch(() -> {
            for (Map<String, Object> target : jobRepository.findPushTargets(event.jobId())) {
                Peer peer = peers.get(uuid(target.get("machineId")));
                if (peer == null) continue;

                Map<String, Object> hint = new LinkedHashMap<>();
                hint.put("type", "jobAvailable");
                hint.put("robotId", String.valueOf(target.get("robotId")));
                send(peer, hint);
            }
        });
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onCommandRequested(AgentPushEvents.CommandRequested event) {
        if (peers.isEmpty() || event.jobId() == null) return;

        dispatch(() -> jobRepository.findAgentMachine(event.jobId()).map(peers::get).ifPresent(peer -> {
            Map<String, Object> hint = new LinkedHashMap<>();
            hint.put("type", "command");
            hint.put("jobId", event.jobId().toString());
            hint.put("command", event.command());
            send(peer, hint);
        }));
    }

    /**
     * Pemeriksaan berkala sambungan yang terbuka: token yang kedaluwarsa,
     * machine key yang diganti/dicabut, dan mesin yang dihapus menutupnya;
     * versi setelan yang berubah dikirim sebagai {@code settingsChanged}; ping
     * menjaga sambungan yang diam supaya tidak diputus proxy.
     */
    @Scheduled(initialDelay = 2_000, fixedDelay = 2_000)
    public void check() {
        if (peers.isEmpty()) return;

        Instant now = clock.instant();
        Map<UUID, MachineRepository.PushState> states;
        try {
            states = machineRepository.findPushStates(List.copyOf(peers.keySet()));
        } catch (RuntimeException e) {
            log.warn("Pemeriksaan sambungan WebSocket Robot Agent gagal: {}", e.getMessage());
            return;
        }

        boolean ping = !now.isBefore(nextPing);
        if (ping) nextPing = now.plus(PING_INTERVAL);

        for (Peer peer : List.copyOf(peers.values())) {
            MachineRepository.PushState state = states.get(peer.machineId());

            String reason = null;
            if (state == null) reason = "Mesin ini sudah dihapus.";
            else if (!Objects.equals(MachineKeys.keyIdOf(state.keyHash()), peer.keyId())) {
                reason = "Machine key mesin ini sudah diganti atau dicabut.";
            } else if (peer.expiresAt() != null && !now.isBefore(peer.expiresAt())) {
                reason = "Token agent kedaluwarsa; masuk lagi.";
            }

            if (reason != null) {
                drop(peer, CLOSE_UNAUTHORIZED, reason);
                continue;
            }

            if (state.settingsVersion() != peer.settingsVersion()[0]) {
                peer.settingsVersion()[0] = state.settingsVersion();
                Map<String, Object> hint = new LinkedHashMap<>();
                hint.put("type", "settingsChanged");
                hint.put("settingsVersion", state.settingsVersion());
                send(peer, hint);
            }

            if (ping) {
                dispatch(() -> {
                    try {
                        peer.connection().ping();
                    } catch (IOException | RuntimeException e) {
                        drop(peer, CLOSE_GOING_AWAY, "Ping gagal.");
                    }
                });
            }
        }
    }

    @PreDestroy
    void shutdown() {
        for (Peer peer : List.copyOf(peers.values())) {
            peer.connection().close(CLOSE_GOING_AWAY, "Open Orchestrator berhenti.");
        }
        peers.clear();
        sender.shutdownNow();
    }

    // ----------------------------------------------------------------- alat

    private static String toJson(Map<String, Object> message) {
        try {
            return JSON.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void send(Peer peer, Map<String, Object> message) {
        String text = toJson(message);

        dispatch(() -> {
            try {
                peer.connection().send(text);
            } catch (IOException | RuntimeException e) {
                log.debug("Petunjuk ke mesin {} gagal dikirim: {}", peer.machineId(), e.getMessage());
                drop(peer, CLOSE_GOING_AWAY, "Pengiriman gagal.");
            }
        });
    }

    private void drop(Peer peer, int code, String reason) {
        peers.remove(peer.machineId(), peer);
        peer.connection().close(code, reason);
    }

    private void dispatch(Runnable task) {
        try {
            sender.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("Petunjuk WebSocket Robot Agent gagal: {}", e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            // Sedang berhenti.
        }
    }

    private static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID u ? u : UUID.fromString(value.toString());
    }
}
