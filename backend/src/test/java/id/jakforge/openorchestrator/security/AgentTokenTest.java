package id.jakforge.openorchestrator.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tiga jenis token, dan apa yang boleh dilakukan masing-masing. */
class AgentTokenTest {

    private final JwtService jwt = new JwtService("k".repeat(32), 480);

    @Test
    @DisplayName("token pengguna lama tetap terbaca sebagai pengguna")
    void userTokenUnchanged() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        OpenOrchestratorPrincipal principal = jwt.parsePrincipal(jwt.issueToken(userId, tenantId, "OO_Admin", "Administrator"));

        assertTrue(principal.isUser());
        assertEquals(userId, principal.userId());
        assertEquals("Administrator", principal.role());
        assertTrue(!principal.isAssistant());
    }

    @Test
    @DisplayName("token Open Assistant: pengguna yang sama, ditambah sambungan yang menerbitkannya")
    void assistantToken() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        var issued = jwt.issueAssistantToken(userId, tenantId, "fajar", "Automation User", sessionId, Duration.ofHours(1));
        OpenOrchestratorPrincipal principal = jwt.parsePrincipal(issued.token());

        assertTrue(principal.isUser());
        assertTrue(principal.isAssistant());
        assertEquals(userId, principal.userId());
        assertEquals("fajar", principal.username());
        assertEquals("Automation User", principal.role());
        assertEquals(sessionId, principal.assistantSessionId());
    }

    @Test
    @DisplayName("token agent membawa mesin dan penanda kuncinya")
    void agentToken() {
        UUID machineId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        var issued = jwt.issueAgentToken(machineId, tenantId, "VM-ROBOT-01", "abcdef0123456789", Duration.ofHours(1));
        OpenOrchestratorPrincipal principal = jwt.parsePrincipal(issued.token());

        assertTrue(principal.isAgent());
        assertEquals(machineId, principal.machineId());
        assertEquals(tenantId, principal.tenantId());
        assertEquals("VM-ROBOT-01", principal.username());
        assertEquals("abcdef0123456789", principal.keyId());
    }

    @Test
    @DisplayName("token executor terikat ke satu robot, satu job, dan foldernya")
    void executorToken() {
        UUID robotId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        OpenOrchestratorPrincipal principal = jwt.parsePrincipal(jwt.issueExecutorToken(robotId, UUID.randomUUID(),
                "Robot_A", jobId, folderId, Duration.ofHours(2)).token());

        assertTrue(principal.isExecutor());
        assertEquals(robotId, principal.robotId());
        assertEquals(jobId, principal.jobId());
        assertEquals(folderId, principal.folderId());
    }

    @Test
    @DisplayName("executor hanya memegang izin activity Orchestrator; agent tidak memegang izin peran apa pun")
    void narrowPermissions() {
        OpenOrchestratorPrincipal executor = OpenOrchestratorPrincipal.executor(UUID.randomUUID(), UUID.randomUUID(),
                "Robot_A", UUID.randomUUID(), null);
        OpenOrchestratorPrincipal agent = OpenOrchestratorPrincipal.agent(UUID.randomUUID(), UUID.randomUUID(),
                "VM", "id");

        PermissionService permissions = new PermissionService(null, null, null);

        for (String allowed : List.of("assets.read", "queues.update", "jobs.create", "jobs.read")) {
            assertTrue(permissions.isAllowed(executor, allowed), allowed);
        }

        for (String denied : List.of("robots.create", "users.update", "roles.update", "jobs.update", "folders.update")) {
            assertFalse(permissions.isAllowed(executor, denied), denied);
        }

        assertTrue(permissions.patternsOf(agent).isEmpty());
    }
}
