package id.jakforge.openorchestrator.security;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.model.UserAccess;
import id.jakforge.openorchestrator.repository.UserRepository;
import id.jakforge.openorchestrator.support.AdjustableClock;
import id.jakforge.openorchestrator.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Izin seseorang: dari peran SAAT INI di basis data, diingat sebentar. */
class PermissionServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC);

    private OpenOrchestratorPrincipal principalWithTokenRole(String role) {
        return new OpenOrchestratorPrincipal(UUID.randomUUID(), tenantId, "budi", role);
    }

    private PermissionService serviceReading(FakeUserRepository users) {
        return new PermissionService(users, TestProperties.defaults(), clock);
    }

    @Test
    @DisplayName("Administrator selalu semuanya, apa pun isi barisnya")
    void administratorHasEverything() {
        FakeUserRepository users = new FakeUserRepository("Administrator", true, "jobs.read");

        assertEquals(Set.of("*"), serviceReading(users).patternsOf(principalWithTokenRole("Administrator")));
    }

    @Test
    @DisplayName("izin mengikuti peran SAAT INI di basis data, bukan peran di token")
    void followsCurrentRoleNotToken() {
        PermissionService permissions = serviceReading(new FakeUserRepository("Auditor", true, "*.read"));

        // Tokennya masih menyebut Administrator; basis data sudah Auditor.
        OpenOrchestratorPrincipal principal = principalWithTokenRole("Administrator");

        assertTrue(permissions.isAllowed(principal, "jobs.read"));
        assertFalse(permissions.isAllowed(principal, "jobs.create"));
    }

    @Test
    @DisplayName("pengguna nonaktif atau yang sudah dihapus tidak punya izin apa pun")
    void inactiveOrDeletedUserHasNothing() {
        assertTrue(serviceReading(new FakeUserRepository("Administrator", false, "*"))
                .patternsOf(principalWithTokenRole("Administrator")).isEmpty());
        assertTrue(serviceReading(new FakeUserRepository(null, true, null))
                .patternsOf(principalWithTokenRole("Robot")).isEmpty());
    }

    @Test
    @DisplayName("diingat sebentar, dan dilupakan seketika sesudah peran berubah")
    void cachesUntilInvalidated() {
        FakeUserRepository users = new FakeUserRepository("Robot", true, "jobs.read");
        PermissionService permissions = serviceReading(users);
        OpenOrchestratorPrincipal principal = principalWithTokenRole("Robot");

        assertTrue(permissions.isAllowed(principal, "jobs.read"));
        assertTrue(permissions.isAllowed(principal, "jobs.read"));
        assertEquals(1, users.reads, "pemeriksaan kedua tidak menyentuh basis data");

        users.permissions = "jobs.update";
        assertTrue(permissions.isAllowed(principal, "jobs.read"), "masih yang lama sampai dilupakan");

        permissions.invalidateCache();

        assertFalse(permissions.isAllowed(principal, "jobs.read"));
        assertTrue(permissions.isAllowed(principal, "jobs.update"));
        assertEquals(2, users.reads);
    }

    @Test
    @DisplayName("ingatannya kedaluwarsa sesudah openorchestrator.permission-cache.ttl")
    void cacheExpiresAfterTtl() {
        FakeUserRepository users = new FakeUserRepository("Robot", true, "jobs.read");
        PermissionService permissions = serviceReading(users);
        OpenOrchestratorPrincipal principal = principalWithTokenRole("Robot");

        permissions.patternsOf(principal);
        users.permissions = "jobs.update";

        clock.advance(Duration.ofSeconds(9));
        assertTrue(permissions.isAllowed(principal, "jobs.read"), "sembilan detik: masih diingat");

        clock.advance(Duration.ofSeconds(1));
        assertTrue(permissions.isAllowed(principal, "jobs.update"), "sepuluh detik: dibaca ulang");
        assertEquals(2, users.reads);
    }

    @Test
    @DisplayName("require() menolak dengan 403 yang menyebut izinnya")
    void requireRejectsWithForbidden() {
        PermissionService permissions = serviceReading(new FakeUserRepository("Auditor", true, "*.read"));

        ApiException error = assertThrows(ApiException.class,
                () -> permissions.require(principalWithTokenRole("Auditor"), "assets.delete"));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
        assertEquals("Peran Anda tidak punya izin 'assets.delete'.", error.getMessage());
    }

    @Test
    @DisplayName("requireSave() meminta create untuk yang baru, update untuk yang sudah ada")
    void requireSaveChoosesAction() {
        PermissionService permissions = serviceReading(new FakeUserRepository("Pembuat", true, "assets.create"));
        OpenOrchestratorPrincipal principal = principalWithTokenRole("Pembuat");

        permissions.requireSave(principal, Permissions.ASSETS, false);

        ApiException error = assertThrows(ApiException.class,
                () -> permissions.requireSave(principal, Permissions.ASSETS, true));
        assertEquals("Peran Anda tidak punya izin 'assets.update'.", error.getMessage());
    }

    @Test
    @DisplayName("Open Assistant: izin peran dipotong ke pekerjaan robot — Administrator pun tidak bisa mengelola penyewa")
    void assistantPermissionsAreNarrowed() {
        OpenOrchestratorPrincipal admin = OpenOrchestratorPrincipal.assistant(UUID.randomUUID(), tenantId, "budi",
                "Administrator", UUID.randomUUID());

        Set<String> granted = serviceReading(new FakeUserRepository("Administrator", true, "*")).patternsOf(admin);

        assertEquals(Set.copyOf(PermissionService.ASSISTANT_PERMISSIONS), granted);
        assertTrue(granted.contains("robots.update") && granted.contains("triggers.read"));
        assertFalse(granted.contains("users.update") || granted.contains("roles.create")
                || granted.contains("robots.create") || granted.contains("*"));
    }

    @Test
    @DisplayName("Open Assistant: tidak pernah LEBIH dari peran pemiliknya")
    void assistantNeverExceedsRole() {
        PermissionService permissions = serviceReading(new FakeUserRepository("Auditor", true, "*.read"));
        OpenOrchestratorPrincipal auditor = OpenOrchestratorPrincipal.assistant(UUID.randomUUID(), tenantId, "budi",
                "Auditor", UUID.randomUUID());

        assertTrue(permissions.isAllowed(auditor, "triggers.read"));
        assertFalse(permissions.isAllowed(auditor, "robots.update"));
        assertFalse(permissions.isAllowed(auditor, "jobs.update"));
        assertFalse(permissions.isAllowed(auditor, "users.read"));
    }

    /** Satu baris users ⨝ roles, tanpa basis data. */
    private static final class FakeUserRepository extends UserRepository {

        final String role;
        final boolean active;
        String permissions;
        int reads;

        FakeUserRepository(String role, boolean active, String permissions) {
            super(null);
            this.role = role;
            this.active = active;
            this.permissions = permissions;
        }

        @Override
        public Optional<UserAccess> findAccess(UUID userId, UUID tenantId) {
            reads++;
            return role == null ? Optional.empty() : Optional.of(new UserAccess(role, active, permissions));
        }
    }
}
