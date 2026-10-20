package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.dto.response.SessionTimeoutSettingsResponse;
import id.jakforge.openorchestrator.repository.SessionSettingsRepository.SettingsRow;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.support.AdjustableClock;
import id.jakforge.openorchestrator.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kebijakan batas waktu menganggur: nilai bawaan, validasi server, dan kebijakan yang mewajibkannya. */
class SessionSettingsServiceTest {

    private final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC);
    private final DashboardSessionServiceTest.MemorySettings settings = new DashboardSessionServiceTest.MemorySettings();
    private final OpenOrchestratorPrincipal admin =
            new OpenOrchestratorPrincipal(UUID.randomUUID(), UUID.randomUUID(), "admin", "Administrator");

    private SessionSettingsService service(OpenOrchestratorProperties.Session session, long jwtMinutes) {
        OpenOrchestratorProperties d = TestProperties.defaults();
        return new SessionSettingsService(settings, new OpenOrchestratorProperties(d.displayTimezone(), d.secret(),
                new OpenOrchestratorProperties.Jwt(d.jwt().secret(), jwtMinutes), d.cors(), d.robot(), d.permissionCache(),
                d.scheduler(), d.limits(), d.bootstrap(), d.agent(), d.queues(), session), clock);
    }

    private SessionSettingsService service() {
        return service(new OpenOrchestratorProperties.Session(2, 480, false), 480);
    }

    private static Map<String, Object> body(Object enabled, Object minutes, Object warning) {
        Map<String, Object> b = new HashMap<>();
        b.put("idleTimeoutEnabled", enabled);
        b.put("idleTimeoutMinutes", minutes);
        b.put("warningBeforeLogoutMinutes", warning);
        return b;
    }

    private static ApiException rejected(SessionSettingsService s, OpenOrchestratorPrincipal p, Map<String, Object> body) {
        ApiException e = assertThrows(ApiException.class, () -> s.update(p, body));
        assertEquals(HttpStatus.BAD_REQUEST, e.status());
        return e;
    }

    @Test
    @DisplayName("tanpa baris: bawaan aktif, 30 menit, peringatan 2 menit, belum pernah diubah")
    void defaults() {
        SessionTimeoutSettingsResponse r = service().get(admin);

        assertTrue(r.idleTimeoutEnabled());
        assertEquals(30, r.idleTimeoutMinutes());
        assertEquals(2, r.warningBeforeLogoutMinutes());
        assertNull(r.updatedAt());
        assertNull(r.updatedBy());
        assertEquals(List.of(5, 10, 15, 30, 60, 120), r.durationOptions());
        assertEquals(480, r.tokenLifetimeMinutes());
    }

    @Test
    @DisplayName("simpan lalu baca lagi: nilainya, siapa, dan kapan")
    void savedAndReadBack() {
        SessionSettingsService s = service();

        SessionTimeoutSettingsResponse r = s.update(admin, body(true, 45, 5));

        assertEquals(45, r.idleTimeoutMinutes());
        assertEquals(5, r.warningBeforeLogoutMinutes());
        assertEquals("admin", r.updatedBy());
        assertTrue(r.updatedAt() != null);
        assertEquals(45, s.policy(admin.tenantId()).idleTimeoutMinutes());
    }

    @Test
    @DisplayName("validasi server: rentang, peringatan < batas, bilangan bulat (15.5 dan \"15\" ditolak), wajib diisi")
    void validation() {
        SessionSettingsService s = service();

        assertEquals("SESSION_TIMEOUT_INVALID", rejected(s, admin, body(true, 1, 0)).errorCode());
        assertEquals("Batas waktu menganggur harus 2–480 menit.", rejected(s, admin, body(true, 481, 2)).getMessage());
        assertTrue(rejected(s, admin, body(true, 5, 5)).getMessage().contains("lebih singkat"));
        assertTrue(rejected(s, admin, body(true, 60, 11)).getMessage().contains("0–10"));
        assertTrue(rejected(s, admin, body(true, 60, -1)).getMessage().contains("0–10"));
        assertTrue(rejected(s, admin, body(true, 15.5, 2)).getMessage().contains("bilangan bulat"));
        assertTrue(rejected(s, admin, body(true, "15", 2)).getMessage().contains("bilangan bulat"));
        assertTrue(rejected(s, admin, body(null, 15, 2)).getMessage().contains("idleTimeoutEnabled"));
        assertTrue(rejected(s, admin, body("ya", 15, 2)).getMessage().contains("idleTimeoutEnabled"));
        assertNull(settings.row, "yang ditolak tidak tersimpan");

        s.update(admin, body(true, 3, 2));
        s.update(admin, body(false, 30, 0));
        assertEquals(false, s.policy(admin.tenantId()).enabled());
    }

    @Test
    @DisplayName("kebijakan server mewajibkan batas waktu: tidak bisa dimatikan, dan yang tersimpan mati tetap ditegakkan")
    void requiredPolicy() {
        settings.row = new SettingsRow(false, 30, 2, Instant.now(), "lama");
        SessionSettingsService s = service(new OpenOrchestratorProperties.Session(2, 480, true), 480);

        assertEquals("SESSION_TIMEOUT_REQUIRED", rejected(s, admin, body(false, 30, 2)).errorCode());
        assertTrue(s.policy(admin.tenantId()).enabled());
        assertTrue(s.get(admin).required());
    }

    @Test
    @DisplayName("batas terpanjang tidak melebihi umur token; nilai lama di luar rentang dijepit ke dalamnya")
    void boundsFollowTokenLifetime() {
        SessionSettingsService s = service(new OpenOrchestratorProperties.Session(2, 480, false), 60);

        assertEquals(60, s.get(admin).maxMinutes());
        assertEquals(List.of(5, 10, 15, 30, 60), s.get(admin).durationOptions());
        assertEquals("Batas waktu menganggur harus 2–60 menit.", rejected(s, admin, body(true, 120, 2)).getMessage());

        settings.row = new SettingsRow(true, 600, 5, Instant.now(), "lama");
        assertEquals(60, service(new OpenOrchestratorProperties.Session(2, 480, false), 60).policy(admin.tenantId()).idleTimeoutMinutes());
    }
}
