package id.jakforge.openorchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.jakforge.openorchestrator.model.JobCommands;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.security.MachineKeys;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.support.AdjustableClock;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Petunjuk WebSocket ke Robot Agent: siapa yang menerimanya, dan kapan sambungan ditutup. */
class AgentPushServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY_HASH = "a".repeat(64);

    private final RecordingDatabase database = new RecordingDatabase();
    private final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-11-02T08:00:00Z"), ZoneOffset.UTC);
    private final UUID tenant = UUID.randomUUID();
    private final UUID machineA = UUID.randomUUID();
    private final UUID machineB = UUID.randomUUID();

    /** Keadaan mesin di "basis data". */
    private final Map<UUID, MachineRepository.PushState> machines = new HashMap<>();
    private final List<Map<String, Object>> pushTargets = new ArrayList<>();
    private final Map<UUID, UUID> jobMachine = new HashMap<>();

    private final AgentPushService push = new AgentPushService(new JobRepository(database) {
        @Override
        public List<Map<String, Object>> findPushTargets(UUID jobId) {
            return pushTargets;
        }

        @Override
        public Optional<UUID> findAgentMachine(UUID jobId) {
            return Optional.ofNullable(jobMachine.get(jobId));
        }
    }, new MachineRepository(database) {
        @Override
        public Optional<Integer> findSettingsVersion(UUID machineId) {
            return Optional.ofNullable(machines.get(machineId)).map(MachineRepository.PushState::settingsVersion);
        }

        @Override
        public Map<UUID, PushState> findPushStates(Collection<UUID> machineIds) {
            Map<UUID, PushState> states = new HashMap<>();
            machineIds.forEach(id -> {
                if (machines.containsKey(id)) states.put(id, machines.get(id));
            });
            return states;
        }
    }, clock, new DirectExecutor());

    /** Sambungan tiruan: mencatat yang dikirim dan cara ditutupnya. */
    static final class FakeConnection implements AgentPushService.Connection {
        final List<String> sent = new ArrayList<>();
        int pings;
        Integer closedWith;
        boolean failSends;

        @Override
        public void send(String text) throws IOException {
            if (failSends) throw new IOException("putus");
            sent.add(text);
        }

        @Override
        public void ping() {
            pings++;
        }

        @Override
        public void close(int code, String reason) {
            if (closedWith == null) closedWith = code;
        }

        List<JsonNode> messages() {
            return new AbstractList<>() {
                @Override
                public JsonNode get(int index) {
                    try {
                        return JSON.readTree(sent.get(index));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }

                @Override
                public int size() {
                    return sent.size();
                }
            };
        }

        JsonNode last() {
            return messages().get(sent.size() - 1);
        }
    }

    /** Menjalankan tugas langsung: uji tidak perlu menunggu utas pengirim. */
    static final class DirectExecutor extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    private OpenOrchestratorPrincipal agent(UUID machineId) {
        return OpenOrchestratorPrincipal.agent(machineId, tenant, "VM-" + machineId.toString().substring(0, 4),
                MachineKeys.keyIdOf(KEY_HASH));
    }

    private FakeConnection connect(UUID machineId) {
        machines.putIfAbsent(machineId, new MachineRepository.PushState(3, KEY_HASH));
        FakeConnection connection = new FakeConnection();
        push.opened(agent(machineId), clock.instant().plus(Duration.ofHours(1)), connection);
        return connection;
    }

    @Test
    @DisplayName("sambungan baru: 'hello' lebih dulu, dengan jenis petunjuk dan versi setelan")
    void hello() {
        FakeConnection a = connect(machineA);

        JsonNode hello = a.messages().getFirst();
        assertEquals("hello", hello.get("type").asText());
        assertEquals(machineA.toString(), hello.get("machineId").asText());
        assertEquals("[\"jobAvailable\",\"command\",\"settingsChanged\"]", hello.get("events").toString());
        assertEquals(3, hello.get("settingsVersion").asInt());
        assertEquals(25, hello.get("pingSeconds").asInt());
        assertTrue(push.isConnected(machineA));
    }

    @Test
    @DisplayName("satu sambungan per mesin: yang baru menggantikan, yang lama ditutup 4000")
    void replaced() {
        FakeConnection first = connect(machineA);
        FakeConnection second = connect(machineA);

        assertEquals(AgentPushService.CLOSE_REPLACED, first.closedWith);
        assertEquals(1, push.connectedCount());

        // Tutupnya sambungan lama tidak melepas yang baru.
        push.closed(machineA, first);
        assertTrue(push.isConnected(machineA));
        push.closed(machineA, second);
        assertFalse(push.isConnected(machineA));
    }

    @Test
    @DisplayName("job baru: jobAvailable hanya ke mesin robot yang bisa mengambilnya")
    void jobAvailable() {
        FakeConnection a = connect(machineA);
        FakeConnection b = connect(machineB);
        UUID robot = UUID.randomUUID();
        pushTargets.add(Map.of("machineId", machineA.toString(), "robotId", robot.toString()));

        push.onJobQueued(new AgentPushEvents.JobQueued(UUID.randomUUID()));

        assertEquals("jobAvailable", a.last().get("type").asText());
        assertEquals(robot.toString(), a.last().get("robotId").asText());
        assertEquals(1, b.sent.size(), "mesin lain hanya menerima hello");
    }

    @Test
    @DisplayName("Stop/Kill/Pause/Resume: command ke mesin yang memegang job-nya")
    void command() {
        FakeConnection a = connect(machineA);
        FakeConnection b = connect(machineB);
        UUID job = UUID.randomUUID();
        jobMachine.put(job, machineB);

        push.onCommandRequested(new AgentPushEvents.CommandRequested(job, JobCommands.KILL));

        assertEquals(1, a.sent.size());
        assertEquals("command", b.last().get("type").asText());
        assertEquals(job.toString(), b.last().get("jobId").asText());
        assertEquals("KillJob", b.last().get("command").asText());

        push.onCommandRequested(new AgentPushEvents.CommandRequested(UUID.randomUUID(), JobCommands.STOP));
        assertEquals(2, b.sent.size(), "job yang tidak dipegang Robot Agent: tidak ada petunjuk");
    }

    @Test
    @DisplayName("versi setelan berubah: settingsChanged sekali")
    void settingsChanged() {
        FakeConnection a = connect(machineA);

        push.check();
        assertEquals(1, a.sent.size(), "tidak berubah: tidak ada petunjuk");

        machines.put(machineA, new MachineRepository.PushState(4, KEY_HASH));
        push.check();
        push.check();

        assertEquals(2, a.sent.size());
        assertEquals("settingsChanged", a.last().get("type").asText());
        assertEquals(4, a.last().get("settingsVersion").asInt());
    }

    @Test
    @DisplayName("machine key diganti atau dicabut, mesin dihapus, token kedaluwarsa: ditutup 4401")
    void closedWhenNoLongerAuthorized() {
        FakeConnection a = connect(machineA);
        FakeConnection b = connect(machineB);
        UUID machineC = UUID.randomUUID();
        FakeConnection c = connect(machineC);

        machines.put(machineA, new MachineRepository.PushState(3, "b".repeat(64)));
        machines.remove(machineB);
        push.check();

        assertEquals(AgentPushService.CLOSE_UNAUTHORIZED, a.closedWith);
        assertEquals(AgentPushService.CLOSE_UNAUTHORIZED, b.closedWith);
        assertEquals(null, c.closedWith);

        machines.put(machineC, new MachineRepository.PushState(3, null));
        push.check();
        assertEquals(AgentPushService.CLOSE_UNAUTHORIZED, c.closedWith, "kunci dicabut");

        UUID machineD = UUID.randomUUID();
        FakeConnection d = connect(machineD);
        clock.advance(Duration.ofHours(1));
        push.check();
        assertEquals(AgentPushService.CLOSE_UNAUTHORIZED, d.closedWith, "token habis umurnya");
        assertEquals(0, push.connectedCount());
    }

    @Test
    @DisplayName("ping tiap 25 detik ke sambungan yang terbuka")
    void ping() {
        FakeConnection a = connect(machineA);

        push.check();
        assertEquals(0, a.pings);

        clock.advance(AgentPushService.PING_INTERVAL);
        push.check();
        push.check();
        assertEquals(1, a.pings);
    }

    @Test
    @DisplayName("pengiriman gagal: sambungan dilepas, petunjuk berikutnya tidak dicoba lagi")
    void failedSendDrops() {
        FakeConnection a = connect(machineA);
        a.failSends = true;
        jobMachine.put(UUID.randomUUID(), machineA);

        push.onCommandRequested(new AgentPushEvents.CommandRequested(jobMachine.keySet().iterator().next(), JobCommands.STOP));

        assertFalse(push.isConnected(machineA));
        assertEquals(AgentPushService.CLOSE_GOING_AWAY, a.closedWith);
    }

    @Test
    @DisplayName("tanpa sambungan: kejadian job tidak menyentuh basis data")
    void noPeersNoQueries() {
        push.onJobQueued(new AgentPushEvents.JobQueued(UUID.randomUUID()));
        push.onCommandRequested(new AgentPushEvents.CommandRequested(UUID.randomUUID(), JobCommands.STOP));
        push.check();

        assertTrue(database.statements.isEmpty());
    }
}
