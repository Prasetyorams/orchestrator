package id.jakforge.openorchestrator.model;

import id.jakforge.openorchestrator.dto.request.AgentHeartbeatRequest;
import id.jakforge.openorchestrator.dto.request.HeartbeatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Usulan tim robot V13–V14: resolusi sesi robot unattended, pemicu jalan, dan
 * "sibuk lokal" di denyut v1 dan v2.
 */
class LocalRunRulesTest {

    @Test
    @DisplayName("resolusi: 0x0 atau pasangan 200..8192; kedalaman 0/15/16/24/32")
    void resolutionRules() {
        assertNull(RobotResolution.DEFAULT.problem());
        assertNull(new RobotResolution(1920, 1080, 32).problem());
        assertNull(new RobotResolution(200, 8192, 0).problem());

        assertTrue(new RobotResolution(1920, 0, 0).problem().startsWith("Resolusi layar: lebar dan tinggi diisi berpasangan"));
        assertNotNull(new RobotResolution(199, 600, 0).problem());
        assertNotNull(new RobotResolution(1920, 8193, 0).problem());
        assertEquals("Kedalaman warna harus 0 (bawaan), 15, 16, 24, atau 32.", new RobotResolution(1366, 768, 12).problem());
    }

    @Test
    @DisplayName("resolusi: yang tidak dikirim tetap; pasangannya diperiksa sesudah digabung")
    void resolutionMerge() {
        RobotResolution current = new RobotResolution(1920, 1080, 32);

        assertEquals(new RobotResolution(1920, 1080, 24), current.with(null, null, 24));
        assertEquals(new RobotResolution(1366, 768, 32), current.with(1366, 768, null));
        assertNotNull(RobotResolution.DEFAULT.with(1920, null, null).problem(), "lebar saja, tinggi tetap 0");
    }

    @Test
    @DisplayName("pemicu: ejaan bebas dibakukan; yang tidak dikenal null; 'job' bukan pemicu lokal")
    void triggers() {
        assertEquals("local-schedule", RunTriggers.normalize(" Local_Schedule "));
        assertEquals("local-schedule", RunTriggers.normalize("localSchedule"));
        assertEquals("manual", RunTriggers.normalize("MANUAL"));
        assertEquals("job", RunTriggers.normalize("job"));
        assertNull(RunTriggers.normalize("cron"));
        assertNull(RunTriggers.normalize(null));

        assertNull(RunTriggers.normalizeLocal("job"));
        assertEquals("manual", RunTriggers.normalizeLocal("manual"));
    }

    @Test
    @DisplayName("sibuk lokal: hanya busyLocal=true; nama dipangkas dan dipotong; pemicu dibakukan")
    void localRun() {
        assertNull(LocalRun.of(null, "Tagihan", "manual"), "robot lama tanpa medannya: tidak sibuk lokal");
        assertNull(LocalRun.of(false, "Tagihan", "manual"));

        assertEquals(new LocalRun("Tagihan", "local-schedule"), LocalRun.of(true, "  Tagihan ", "local_schedule"));
        assertEquals(new LocalRun(null, null), LocalRun.of(true, " ", "cron"));
        assertEquals(LocalRun.MAX_NAME_LENGTH, LocalRun.of(true, "x".repeat(500), null).name().length());
    }

    @Test
    @DisplayName("denyut v1 membawa busyLocal, busyLocalName, busyLocalTrigger")
    void v1Heartbeat() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "busy");
        body.put("busyLocal", true);
        body.put("busyLocalName", "Tagihan");
        body.put("busyLocalTrigger", "manual");

        assertEquals(new LocalRun("Tagihan", "manual"), HeartbeatRequest.fromBody(body).localRun());
        assertNull(HeartbeatRequest.fromBody(Map.of("status", "AVAILABLE")).localRun());
    }

    @Test
    @DisplayName("denyut Robot Agent: busyLocal per robot")
    void v2Heartbeat() {
        UUID busy = UUID.randomUUID();
        UUID idle = UUID.randomUUID();

        AgentHeartbeatRequest request = AgentHeartbeatRequest.fromBody(Map.of("robots", List.of(
                Map.of("robotId", busy.toString(), "state", "Idle", "busyLocal", true, "busyLocalName", "Rekap",
                        "busyLocalTrigger", "local-schedule"),
                Map.of("robotId", idle.toString(), "state", "Idle"))));

        assertEquals(new LocalRun("Rekap", "local-schedule"), request.robots().get(0).localRun());
        assertNull(request.robots().get(1).localRun());
    }
}
