package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.dto.response.SessionStateResponse;
import id.jakforge.openorchestrator.repository.AuditRepository;
import id.jakforge.openorchestrator.repository.DashboardSessionRepository;
import id.jakforge.openorchestrator.repository.DashboardSessionRepository.SessionRow;
import id.jakforge.openorchestrator.repository.SessionSettingsRepository;
import id.jakforge.openorchestrator.repository.SessionSettingsRepository.SettingsRow;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.support.AdjustableClock;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import id.jakforge.openorchestrator.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Batas waktu menganggur sesi dasbor ditegakkan server, dengan jam palsu. */
class DashboardSessionServiceTest {

    private final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    /** Meniru SQL DashboardSessionRepository di memori, termasuk syarat UPDATE-nya. */
    static class MemorySessions extends DashboardSessionRepository {
        final Map<UUID, SessionRow> rows = new HashMap<>();
        int finds;

        MemorySessions() {
            super(null);
        }

        @Override
        public void insert(UUID id, UUID tenantId, UUID userId, String username, Instant now, Instant expiresAt,
                           String userAgent) {
            rows.put(id, new SessionRow(id, tenantId, userId, username, now, now, expiresAt, null, null));
        }

        @Override
        public Optional<SessionRow> find(UUID tenantId, UUID id) {
            finds++;
            return Optional.ofNullable(rows.get(id)).filter(r -> r.tenantId().equals(tenantId));
        }

        @Override
        public boolean recordActivity(UUID tenantId, UUID id, Instant now, Instant idleCutoff) {
            SessionRow r = rows.get(id);
            if (r == null || r.revokedAt() != null || !r.expiresAt().isAfter(now)) return false;
            if (idleCutoff != null && !r.lastActivityAt().isAfter(idleCutoff)) return false;
            rows.put(id, new SessionRow(r.id(), r.tenantId(), r.userId(), r.username(), r.createdAt(), now,
                    r.expiresAt(), null, null));
            return true;
        }

        @Override
        public boolean revoke(UUID tenantId, UUID id, Instant now, String reason) {
            SessionRow r = rows.get(id);
            if (r == null || r.revokedAt() != null) return false;
            rows.put(id, new SessionRow(r.id(), r.tenantId(), r.userId(), r.username(), r.createdAt(),
                    r.lastActivityAt(), r.expiresAt(), now, reason));
            return true;
        }

        @Override
        public int revokeAllOfUser(UUID tenantId, String username, Instant now, String reason, UUID exceptId) {
            int n = 0;
            for (SessionRow r : rows.values().stream().toList()) {
                if (r.username().equals(username) && !r.id().equals(exceptId) && revoke(tenantId, r.id(), now, reason)) n++;
            }
            return n;
        }

        @Override
        public void deleteStale(Instant now) {
        }
    }

    static class MemorySettings extends SessionSettingsRepository {
        SettingsRow row;

        MemorySettings() {
            super(null);
        }

        @Override
        public Optional<SettingsRow> find(UUID tenantId) {
            return Optional.ofNullable(row);
        }

        @Override
        public void save(UUID tenantId, boolean enabled, int idleTimeoutMinutes, int warning, String updatedBy) {
            row = new SettingsRow(enabled, idleTimeoutMinutes, warning, Instant.now(), updatedBy);
        }
    }

    private final MemorySessions sessions = new MemorySessions();
    private final MemorySettings settings = new MemorySettings();
    private final RecordingDatabase auditDatabase = new RecordingDatabase();
    private final SessionSettingsService settingsService =
            new SessionSettingsService(settings, TestProperties.defaults(), clock);
    private final DashboardSessionService service = new DashboardSessionService(sessions, settingsService,
            new AuditService(new AuditRepository(auditDatabase), null, null, null, null), clock);

    private OpenOrchestratorPrincipal login() {
        UUID id = service.start(tenantId, userId, "dina", clock.instant().plus(Duration.ofHours(8)), "Mozilla/5.0");
        return OpenOrchestratorPrincipal.dashboard(userId, tenantId, "dina", "Administrator", id);
    }

    private static ApiException rejected(Executable action) {
        ApiException e = assertThrows(ApiException.class, action);
        assertEquals(HttpStatus.UNAUTHORIZED, e.status());
        return e;
    }

    private void admin(boolean enabled, int minutes, int warning) {
        OpenOrchestratorPrincipal admin = new OpenOrchestratorPrincipal(UUID.randomUUID(), tenantId, "admin", "Administrator");
        settingsService.update(admin, Map.of("idleTimeoutEnabled", enabled, "idleTimeoutMinutes", minutes,
                "warningBeforeLogoutMinutes", warning));
    }

    @Test
    @DisplayName("menganggur melewati batas (bawaan 30 menit): 401 SessionIdleTimeout, dicabut sekali, diaudit sekali")
    void idleSessionRejected() {
        OpenOrchestratorPrincipal p = login();

        clock.advance(Duration.ofMinutes(29));
        assertDoesNotThrow(() -> service.requireActive(p));

        clock.advance(Duration.ofMinutes(1).plus(DashboardSessionService.GRACE).plusSeconds(1));
        ApiException e = rejected(() -> service.requireActive(p));
        assertEquals("SessionIdleTimeout", e.errorCode());
        assertEquals("Sesi berakhir karena tidak ada aktivitas selama 30 menit. Silakan masuk lagi.", e.getMessage());
        assertEquals("idle_timeout", sessions.rows.get(p.dashboardSessionId()).revokeReason());

        // Permintaan berikutnya tetap ditolak dengan alasan yang sama, tanpa jejak audit kedua.
        assertEquals("SessionIdleTimeout", rejected(() -> service.requireActive(p)).errorCode());
        assertEquals(1, auditDatabase.statementsContaining("INSERT INTO audit_logs").size());
        assertEquals("Berakhir karena tidak aktif", auditDatabase.argumentsOf("INSERT INTO audit_logs").get(3));
    }

    @Test
    @DisplayName("aktivitas orang memperpanjang; pemeriksaan biasa (penarikan data berkala) TIDAK")
    void activityExtendsPollingDoesNot() {
        OpenOrchestratorPrincipal p = login();

        clock.advance(Duration.ofMinutes(20));
        SessionStateResponse state = service.recordActivity(p);
        assertEquals("2026-10-20T08:50:00Z", state.idleExpiresAt().replace(".000000", ""));

        // 25 menit penarikan data tiap 10 detik, tanpa aktivitas orang.
        for (int i = 0; i < 150; i++) {
            clock.advance(Duration.ofSeconds(10));
            service.requireActive(p);
            service.state(p);
        }
        clock.advance(Duration.ofMinutes(5).plus(DashboardSessionService.GRACE).plusSeconds(1));
        assertEquals("SessionIdleTimeout", rejected(() -> service.requireActive(p)).errorCode());
    }

    @Test
    @DisplayName("sesi yang sudah lewat batas tidak bisa diperpanjang lewat laporan aktivitas (Tetap masuk terlambat)")
    void expiredCannotBeExtended() {
        OpenOrchestratorPrincipal p = login();
        clock.advance(Duration.ofMinutes(31));

        assertEquals("SessionIdleTimeout", rejected(() -> service.recordActivity(p)).errorCode());
        assertEquals("idle_timeout", sessions.rows.get(p.dashboardSessionId()).revokeReason());
        assertEquals("SessionIdleTimeout", rejected(() -> service.recordActivity(p)).errorCode());
    }

    @Test
    @DisplayName("keluar: token yang masih berumur ditolak, dan tidak bisa dihidupkan lagi oleh laporan aktivitas")
    void logoutEndsToken() {
        OpenOrchestratorPrincipal p = login();
        service.requireActive(p);

        service.logout(p);

        assertEquals("SessionEnded", rejected(() -> service.requireActive(p)).errorCode());
        assertEquals("SessionEnded", rejected(() -> service.recordActivity(p)).errorCode());
    }

    @Test
    @DisplayName("batas dimatikan: tidak ada batas menganggur, tetapi umur token tetap batas mutlak")
    void disabledKeepsAbsoluteExpiry() {
        admin(false, 30, 2);
        OpenOrchestratorPrincipal p = login();

        clock.advance(Duration.ofHours(7).plusMinutes(59));
        assertDoesNotThrow(() -> service.requireActive(p));
        assertNull(service.state(p).idleExpiresAt());

        clock.advance(Duration.ofMinutes(2));
        assertEquals("SessionEnded", rejected(() -> service.requireActive(p)).errorCode());
    }

    @Test
    @DisplayName("perubahan kebijakan berlaku untuk sesi yang SUDAH ada, bukan hanya peramban admin")
    void policyChangeAppliesToExistingSessions() {
        OpenOrchestratorPrincipal p = login();
        clock.advance(Duration.ofMinutes(20));
        service.requireActive(p);

        admin(true, 15, 2);

        assertEquals("SessionIdleTimeout", rejected(() -> service.requireActive(p)).errorCode());
    }

    @Test
    @DisplayName("satu sesi per masuk: aktivitas sesi A tidak memperpanjang sesi B orang yang sama")
    void sessionsAreIndependent() {
        OpenOrchestratorPrincipal a = login();
        OpenOrchestratorPrincipal b = login();

        clock.advance(Duration.ofMinutes(20));
        service.recordActivity(a);
        clock.advance(Duration.ofMinutes(11));

        assertDoesNotThrow(() -> service.requireActive(a));
        assertEquals("SessionIdleTimeout", rejected(() -> service.requireActive(b)).errorCode());
    }

    @Test
    @DisplayName("ganti sandi: sesi lain berakhir, sesi yang dipakai dibiarkan")
    void revokeAllExceptCurrent() {
        OpenOrchestratorPrincipal current = login();
        OpenOrchestratorPrincipal other = login();
        service.requireActive(other);

        service.revokeAllOf(tenantId, "dina", "password_changed", current.dashboardSessionId());

        assertDoesNotThrow(() -> service.requireActive(current));
        assertEquals("SessionEnded", rejected(() -> service.requireActive(other)).errorCode());
    }

    @Test
    @DisplayName("token tanpa sesi dasbor (Studio, robot): keadaan 'token', laporan dan keluar tidak berefek")
    void plainTokensUnaffected() {
        OpenOrchestratorPrincipal studio = new OpenOrchestratorPrincipal(userId, tenantId, "dina", "Administrator");

        assertEquals("token", service.state(studio).kind());
        assertEquals("token", service.recordActivity(studio).kind());
        service.logout(studio);
        assertEquals(0, sessions.rows.size());
    }

    @Test
    @DisplayName("baris sesi di-cache sebentar: penarikan data beruntun tidak menghantam basis data tiap kali")
    void rowIsCached() {
        OpenOrchestratorPrincipal p = login();
        int before = sessions.finds;

        for (int i = 0; i < 20; i++) service.requireActive(p);

        assertEquals(before + 1, sessions.finds);
    }
}
