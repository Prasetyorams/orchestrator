package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.config.AdProperties;
import id.jakforge.openorchestrator.dto.request.LoginRequest;
import id.jakforge.openorchestrator.dto.response.LoginResponse;
import id.jakforge.openorchestrator.repository.AssistantSessionRepository;
import id.jakforge.openorchestrator.repository.AuditRepository;
import id.jakforge.openorchestrator.repository.DashboardSessionRepository;
import id.jakforge.openorchestrator.repository.SessionSettingsRepository;
import id.jakforge.openorchestrator.repository.TenantRepository;
import id.jakforge.openorchestrator.repository.UserRepository;
import id.jakforge.openorchestrator.security.JwtService;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.PermissionService;
import id.jakforge.openorchestrator.security.ad.ActiveDirectory;
import id.jakforge.openorchestrator.security.ad.AdAccount;
import id.jakforge.openorchestrator.security.ad.AdException;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import id.jakforge.openorchestrator.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tabel aturan masuk Windows AD: hanya pengguna yang didaftarkan, aktif, dengan identitas yang cocok. */
class AdLoginServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID guid = UUID.randomUUID();
    private final RecordingDatabase database = new RecordingDatabase();
    private final JwtService jwtService = new JwtService("k".repeat(32), 60);

    /** Hasil AD yang diatur per uji. */
    private AdAccount account = account("dina", guid, true);
    private AdException failure;

    /** Pengguna yang "terdaftar" di basis data tiruan. */
    private final List<Map<String, Object>> users = new ArrayList<>();
    private final List<String> linked = new ArrayList<>();
    private final List<String> renamed = new ArrayList<>();
    private boolean loginRecorded;

    private static AdAccount account(String sam, UUID guid, boolean allowed) {
        return new AdAccount(guid, sam, sam + "@uji.local", "Dina Aulia", sam + "@contoh.test",
                "CN=Dina Aulia,OU=Users,DC=uji,DC=local", true, allowed);
    }

    private Map<String, Object> registered(String adUsername, UUID adGuid, boolean active) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", userId.toString());
        row.put("tenantId", tenantId.toString());
        row.put("tenantName", "default");
        row.put("username", "dina.internal");
        row.put("displayName", "Dina");
        row.put("role", "Automation User");
        row.put("isActive", active);
        row.put("adUsername", adUsername);
        row.put("adObjectGuid", adGuid == null ? null : adGuid.toString());
        users.add(row);
        return row;
    }

    private AdLoginService service(boolean adEnabled) {
        ActiveDirectory directory = new ActiveDirectory(AdProperties.disabled()) {
            @Override
            public boolean enabled() {
                return adEnabled;
            }

            @Override
            public AdAccount authenticate(String username, String password) {
                if (failure != null) throw failure;
                return account;
            }

            @Override
            public String directory() {
                return "DC=uji,DC=local";
            }
        };

        UserRepository userRepository = new UserRepository(database) {
            @Override
            public Optional<Map<String, Object>> findAdUserByGuid(UUID objectGuid) {
                return users.stream().filter(u -> objectGuid.toString().equals(u.get("adObjectGuid"))).findFirst();
            }

            @Override
            public Optional<Map<String, Object>> findAdUserByAdUsername(String adUsername) {
                return users.stream().filter(u -> adUsername.equalsIgnoreCase((String) u.get("adUsername"))).findFirst();
            }

            @Override
            public boolean linkAdIdentityIfUnlinked(UUID id, UUID objectGuid, String dir, String sam) {
                linked.add(objectGuid + "|" + dir + "|" + sam);
                users.forEach(u -> u.put("adObjectGuid", objectGuid.toString()));
                return true;
            }

            @Override
            public void updateAdUsername(UUID id, String sam) {
                renamed.add(sam);
            }

            @Override
            public void recordLogin(UUID id) {
                loginRecorded = true;
            }
        };

        TenantRepository tenants = new TenantRepository(database) {
            @Override
            public Optional<UUID> findFirstId() {
                return Optional.of(tenantId);
            }
        };

        SessionSettingsService settings = new SessionSettingsService(new SessionSettingsRepository(database),
                TestProperties.defaults(), Clock.systemUTC());
        AuthService auth = new AuthService(userRepository, jwtService,
                new PermissionService(userRepository, TestProperties.defaults(), Clock.systemUTC()),
                new AssistantSignInService(new AssistantSessionRepository(database), userRepository, null, jwtService, Clock.systemUTC()),
                new DashboardSessionService(new DashboardSessionRepository(database), settings, null, Clock.systemUTC()));

        return new AdLoginService(directory, userRepository, tenants, new AuditRepository(database), auth);
    }

    private ApiException rejected(HttpStatus status, String code) {
        ApiException e = assertThrows(ApiException.class,
                () -> service(true).login(new LoginRequest("dina", "Sandi-Dina-1", "dashboard"), "Mozilla/5.0"));
        assertEquals(status, e.status());
        assertEquals(code, e.errorCode());
        assertTrue(database.statementsContaining("INSERT INTO users").isEmpty(), "tidak pernah membuat pengguna");
        assertTrue(database.statementsContaining("INSERT INTO dashboard_sessions").isEmpty(), "tidak ada sesi");
        assertTrue(!loginRecorded, "tidak tercatat masuk");
        return e;
    }

    private List<Object> lastAudit() {
        return database.argumentsOf("INSERT INTO audit_logs");
    }

    @Test
    @DisplayName("terdaftar, aktif, GUID cocok: token sesi dasbor, masuk tercatat, diaudit")
    void allowed() {
        registered("dina", guid, true);

        LoginResponse r = service(true).login(new LoginRequest("dina", "Sandi-Dina-1", "dashboard"), "Mozilla/5.0");

        OpenOrchestratorPrincipal p = jwtService.parsePrincipal(r.token());
        assertEquals("dina.internal", p.username());
        assertEquals(userId, p.userId());
        assertTrue(p.isDashboard(), "batas waktu sesi menganggur (V18) berlaku juga untuk masuk AD");
        assertTrue(loginRecorded);
        assertTrue(database.statementsContaining("INSERT INTO dashboard_sessions").size() == 1);
        assertTrue(lastAudit().contains("Masuk (Windows AD)"));
    }

    @Test
    @DisplayName("kredensial AD salah: 401 dengan pesan aman; gagal diaudit")
    void invalidCredentials() {
        registered("dina", guid, true);
        failure = new AdException(AdException.Failure.INVALID_CREDENTIALS, "bind ditolak: data 52e");

        ApiException e = rejected(HttpStatus.UNAUTHORIZED, "AD_INVALID_CREDENTIALS");
        assertEquals(AdLoginService.INVALID_MESSAGE, e.getMessage());
        assertTrue(lastAudit().contains("Masuk Windows AD gagal"));
        assertTrue(!String.valueOf(lastAudit()).contains("Sandi-Dina-1"), "sandi tidak pernah masuk audit");
    }

    @Test
    @DisplayName("akun AD terkunci/nonaktif: pesan yang sama dengan sandi salah")
    void lockedLooksTheSame() {
        failure = new AdException(AdException.Failure.ACCOUNT_LOCKED, "data 775");
        assertEquals(AdLoginService.INVALID_MESSAGE, rejected(HttpStatus.UNAUTHORIZED, "AD_INVALID_CREDENTIALS").getMessage());
    }

    @Test
    @DisplayName("sandi AD benar tetapi belum didaftarkan: ditolak, tanpa membuat pengguna atau peran bawaan")
    void notRegistered() {
        rejected(HttpStatus.FORBIDDEN, "AD_NOT_REGISTERED");
        assertTrue(lastAudit().contains("Masuk Windows AD ditolak"));
        assertTrue(lastAudit().contains("belum didaftarkan di Open Orchestrator"));
    }

    @Test
    @DisplayName("terdaftar tetapi nonaktif: ditolak")
    void inactive() {
        registered("dina", guid, false);
        rejected(HttpStatus.FORBIDDEN, "AD_USER_INACTIVE");
    }

    @Test
    @DisplayName("nama sama, GUID lain (akun dihapus lalu dibuat ulang): ditolak, tidak ditautkan otomatis")
    void identityMismatch() {
        registered("dina", UUID.randomUUID(), true);
        rejected(HttpStatus.FORBIDDEN, "AD_IDENTITY_MISMATCH");
        assertTrue(linked.isEmpty());
    }

    @Test
    @DisplayName("didaftarkan tanpa GUID (tanpa akun layanan): GUID ditautkan sekali saat masuk pertama")
    void firstLoginLinks() {
        registered("dina", null, true);

        service(true).login(new LoginRequest("dina", "Sandi-Dina-1", "dashboard"), null);

        assertEquals(List.of(guid + "|DC=uji,DC=local|dina"), linked);
        assertTrue(database.argumentsOf("INSERT INTO audit_logs").contains("Tautkan identitas Windows AD"));
    }

    @Test
    @DisplayName("nama AD diganti di direktori, GUID sama: masuk, nama yang tersimpan diperbarui")
    void renamedInAd() {
        registered("dina.lama", guid, true);

        service(true).login(new LoginRequest("dina", "Sandi-Dina-1", "dashboard"), null);

        assertEquals(List.of("dina"), renamed);
    }

    @Test
    @DisplayName("bukan anggota grup AD yang diizinkan: ditolak walau terdaftar dan aktif")
    void groupNotAllowed() {
        registered("dina", guid, true);
        account = account("dina", guid, false);
        rejected(HttpStatus.FORBIDDEN, "AD_GROUP_NOT_ALLOWED");
    }

    @Test
    @DisplayName("AD tidak terjangkau / TLS ditolak: 503 dengan pesan yang sesuai")
    void unavailable() {
        registered("dina", guid, true);
        for (AdException.Failure f : List.of(AdException.Failure.UNREACHABLE, AdException.Failure.TLS, AdException.Failure.DIRECTORY_ERROR)) {
            failure = new AdException(f, "uji");
            ApiException e = rejected(HttpStatus.SERVICE_UNAVAILABLE, "AD_UNAVAILABLE");
            assertTrue(e.getMessage().startsWith("Windows AD tidak dapat dihubungi"), e.getMessage());
        }
    }

    @Test
    @DisplayName("masuk AD dimatikan di server: 400 AD_DISABLED")
    void disabled() {
        ApiException e = assertThrows(ApiException.class,
                () -> service(false).login(new LoginRequest("dina", "Sandi-Dina-1", "dashboard"), null));
        assertEquals("AD_DISABLED", e.errorCode());
    }
}
