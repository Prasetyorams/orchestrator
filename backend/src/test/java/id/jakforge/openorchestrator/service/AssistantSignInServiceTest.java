package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.dto.request.AssistantCodeRequest;
import id.jakforge.openorchestrator.dto.request.AssistantTokenRequest;
import id.jakforge.openorchestrator.dto.response.AssistantCodeResponse;
import id.jakforge.openorchestrator.repository.AssistantSessionRepository;
import id.jakforge.openorchestrator.repository.UserRepository;
import id.jakforge.openorchestrator.security.AssistantSignIn;
import id.jakforge.openorchestrator.security.JwtService;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.support.AdjustableClock;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aturan masuk Open Assistant lewat dasbor, dengan basis data perekam: yang
 * diuji keputusan layanannya DAN kalimat SQL yang dikirimnya. Alur lengkap
 * melawan PostgreSQL ada di uji ujung-ke-ujung.
 */
class AssistantSignInServiceTest {

    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String STATE = "abcdefgh12345678";

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-10-01T03:00:00Z"), ZoneOffset.UTC);
    private final RecordingDatabase database = new RecordingDatabase();
    private final JwtService jwtService = new JwtService("k".repeat(32), 480);

    private final AssistantSignInService service = new AssistantSignInService(
            new AssistantSessionRepository(database), new ActiveUser(), null, jwtService, clock);

    private final OpenOrchestratorPrincipal dashboard = new OpenOrchestratorPrincipal(userId, tenantId, "fajar", "Automation User");

    private static AssistantCodeRequest request(String client, String redirect, String challenge, String method,
                                                String state) {
        return new AssistantCodeRequest(client, redirect, challenge, method, state, "DESKTOP-01");
    }

    private static AssistantCodeRequest validRequest() {
        return request(AssistantSignIn.CLIENT, AssistantSignIn.REDIRECT_URI, CHALLENGE, "S256", STATE);
    }

    // ---------- dasbor: kode ----------

    @Test
    @DisplayName("kode: yang disimpan hash-nya, yang dikirim ke peramban tautan openassistant:// dengan state")
    void codeIsHashedAndRedirectCarriesState() {
        AssistantCodeResponse response = service.createCode(dashboard, validRequest());

        URI redirect = URI.create(response.redirectUrl());
        assertEquals("openassistant", redirect.getScheme());
        assertTrue(redirect.getRawQuery().endsWith("&state=" + STATE), redirect.toString());

        String code = redirect.getRawQuery().substring("code=".length(), redirect.getRawQuery().indexOf('&'));
        var stored = database.argumentsOf("INSERT INTO assistant_codes");

        assertEquals(AssistantSignIn.hash(code), stored.get(3));
        assertFalse(stored.contains(code));
        assertEquals(CHALLENGE, stored.get(6));
        assertEquals("DESKTOP-01", stored.get(7));
        assertEquals(OffsetDateTime.parse("2026-10-01T03:01:00Z"), stored.get(8));
    }

    @Test
    @DisplayName("kode: aplikasi, alamat kembali, metode, challenge, dan state yang salah ditolak 400 invalid_request")
    void codeRequestValidated() {
        for (AssistantCodeRequest bad : new AssistantCodeRequest[] {
                request("lain", AssistantSignIn.REDIRECT_URI, CHALLENGE, "S256", STATE),
                request(AssistantSignIn.CLIENT, "https://jahat.example/ambil", CHALLENGE, "S256", STATE),
                request(AssistantSignIn.CLIENT, AssistantSignIn.REDIRECT_URI, CHALLENGE, "plain", STATE),
                request(AssistantSignIn.CLIENT, AssistantSignIn.REDIRECT_URI, "pendek", "S256", STATE),
                request(AssistantSignIn.CLIENT, AssistantSignIn.REDIRECT_URI, CHALLENGE, "S256", "x"),
                request(AssistantSignIn.CLIENT, AssistantSignIn.REDIRECT_URI, null, null, null) }) {
            ApiException error = assertThrows(ApiException.class, () -> service.createCode(dashboard, bad));
            assertEquals(HttpStatus.BAD_REQUEST, error.status());
            assertEquals("invalid_request", error.errorCode());
        }

        assertTrue(database.statementsContaining("INSERT INTO assistant_codes").isEmpty());
    }

    @Test
    @DisplayName("kode: token Open Assistant sendiri tidak bisa menyetujui sambungan baru")
    void assistantCannotApproveConnections() {
        var assistant = OpenOrchestratorPrincipal.assistant(userId, tenantId, "fajar", "Automation User", UUID.randomUUID());

        ApiException error = assertThrows(ApiException.class, () -> service.createCode(assistant, validRequest()));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
    }

    // ---------- Open Assistant: tukar kode ----------

    @Test
    @DisplayName("tukar: kode yang sudah dipakai mencabut sambungan dari kode itu dan ditolak invalid_grant")
    void reusedCodeRevokesItsSessions() {
        database.answerRow("FROM assistant_codes", codeRow(true, true));

        ApiException error = assertThrows(ApiException.class,
                () -> service.exchange(new AssistantTokenRequest("kode", VERIFIER, "DESKTOP-01", "1.0")));

        assertEquals("invalid_grant", error.errorCode());
        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertEquals("code_reused", database.argumentsOf("WHERE code_id = ?").getFirst());
        assertTrue(database.statementsContaining("INSERT INTO assistant_sessions").isEmpty());
    }

    @Test
    @DisplayName("tukar: verifier salah tetap MENGHABISKAN kodenya, dan ditolak invalid_grant")
    void wrongVerifierBurnsCode() {
        database.answerRow("FROM assistant_codes", codeRow(true, false));

        ApiException error = assertThrows(ApiException.class, () -> service.exchange(
                new AssistantTokenRequest("kode", VERIFIER.replace('d', 'e'), "DESKTOP-01", null)));

        assertEquals("invalid_grant", error.errorCode());
        assertEquals(1, database.statementsContaining("UPDATE assistant_codes SET used_at").size());
        assertTrue(database.statementsContaining("INSERT INTO assistant_sessions").isEmpty());
    }

    @Test
    @DisplayName("tukar: kode kedaluwarsa ditolak walau verifier-nya benar")
    void expiredCodeRejected() {
        database.answerRow("FROM assistant_codes", codeRow(false, false));

        ApiException error = assertThrows(ApiException.class,
                () -> service.exchange(new AssistantTokenRequest("kode", VERIFIER, "DESKTOP-01", null)));

        assertEquals("invalid_grant", error.errorCode());
    }

    @Test
    @DisplayName("tukar dan perbarui: medan wajib yang kosong dijawab 400 invalid_request")
    void missingFields() {
        assertEquals("invalid_request", assertThrows(ApiException.class,
                () -> service.exchange(new AssistantTokenRequest(null, VERIFIER, null, null))).errorCode());
        assertEquals("invalid_request", assertThrows(ApiException.class, () -> service.refresh(null)).errorCode());
    }

    @Test
    @DisplayName("perbarui: sambungan yang dicabut atau kedaluwarsa dijawab 401 invalid_grant")
    void refreshOfEndedSession() {
        Map<String, Object> session = new HashMap<>();
        session.put("id", UUID.randomUUID().toString());
        session.put("live", false);
        database.answerRow("FROM assistant_sessions", session);

        ApiException error = assertThrows(ApiException.class, () -> service.refresh("refresh-lama"));

        assertEquals(HttpStatus.UNAUTHORIZED, error.status());
        assertEquals("invalid_grant", error.errorCode());
        assertTrue(database.statementsContaining("SET refresh_hash").isEmpty());
    }

    // ---------- token yang sedang dipakai ----------

    @Test
    @DisplayName("sambungan aktif diingat 30 detik: denyut berikutnya tidak menjadi kueri")
    void activeSessionIsCached() {
        var assistant = OpenOrchestratorPrincipal.assistant(userId, tenantId, "fajar", "Automation User", UUID.randomUUID());

        service.requireActive(assistant);
        service.requireActive(assistant);
        assertEquals(1, database.statementsContaining("SET last_used_at").size());

        clock.advance(Duration.ofSeconds(31));
        service.requireActive(assistant);
        assertEquals(2, database.statementsContaining("SET last_used_at").size());
    }

    @Test
    @DisplayName("mencabut dari dasbor langsung berlaku, tidak menunggu ingatan 30 detik habis")
    void revokeTakesEffectImmediately() {
        UUID sessionId = UUID.randomUUID();
        var assistant = OpenOrchestratorPrincipal.assistant(userId, tenantId, "fajar", "Automation User", sessionId);

        service.requireActive(assistant);
        service.revoke(dashboard, sessionId.toString());

        // Sesudah dicabut, basis data (yang di sini selalu menjawab "1 baris") ditanya lagi:
        // yang diingat sudah dibuang.
        service.requireActive(assistant);
        assertEquals(2, database.statementsContaining("SET last_used_at").size());
        assertEquals("revoked", database.argumentsOf("AND user_id = ? AND revoked_at IS NULL").getFirst());
    }

    @Test
    @DisplayName("cabut: id yang bukan UUID dijawab 404")
    void revokeUnknownId() {
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> service.revoke(dashboard, "bukan-uuid")).status());
    }

    private Map<String, Object> codeRow(boolean live, boolean used) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", UUID.randomUUID().toString());
        row.put("tenantId", tenantId.toString());
        row.put("userId", userId.toString());
        row.put("codeChallenge", CHALLENGE);
        row.put("machineName", "DESKTOP-01");
        row.put("live", live);
        row.put("used", used);
        return row;
    }

    /** Pengguna aktif, tanpa basis data. */
    private final class ActiveUser extends UserRepository {

        ActiveUser() {
            super(null);
        }

        @Override
        public Optional<Map<String, Object>> findProfile(UUID id, UUID tenant) {
            Map<String, Object> profile = new HashMap<>();
            profile.put("id", userId.toString());
            profile.put("username", "fajar");
            profile.put("displayName", "Fajar");
            profile.put("role", "Automation User");
            profile.put("isActive", true);
            return Optional.of(profile);
        }
    }
}
