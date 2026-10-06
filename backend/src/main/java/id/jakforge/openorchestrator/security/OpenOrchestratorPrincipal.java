package id.jakforge.openorchestrator.security;

import java.util.UUID;

/**
 * Siapa yang sedang memanggil API.
 *
 * <p>tenantId ikut dibawa di sini supaya setiap service bisa menyaring
 * datanya tanpa menerima tenantId dari badan permintaan — nilai yang
 * datang dari klien tidak boleh menentukan data siapa yang terlihat.
 *
 * <p>Tiga jenis pemanggil:
 *
 * <ul>
 *   <li>{@link Kind#USER} — orang, atau robot v1 yang masuk dengan akun
 *       pengguna. {@code userId} adalah id penggunanya.</li>
 *   <li>{@link Kind#AGENT} — Robot Agent yang masuk dengan machine key.
 *       {@code userId} dan {@code machineId} sama-sama id mesinnya, dan
 *       {@code username} nama mesinnya. Hanya berlaku di /api/agent.</li>
 *   <li>{@link Kind#EXECUTOR} — Executor yang menjalankan SATU job, lewat
 *       token yang diberikan saat job diambil. {@code userId} adalah id
 *       robotnya. Tokennya berhenti berlaku begitu job-nya selesai.</li>
 * </ul>
 *
 * <p>Pengguna yang masuk lewat Open Assistant ("masuk lewat dasbor") tetap
 * {@link Kind#USER}, dengan {@code assistantSessionId}: tokennya bisa dicabut
 * dari dasbor, dan izinnya dipersempit ke pekerjaan robot (PermissionService).
 */
public record OpenOrchestratorPrincipal(UUID userId, UUID tenantId, String username, String role,
                                        Kind kind, UUID machineId, UUID robotId, UUID jobId, UUID folderId,
                                        String keyId, UUID assistantSessionId) {

    public enum Kind { USER, AGENT, EXECUTOR }

    /** Tertulis di token agent dan executor; tidak pernah menjadi nama peran sungguhan. */
    public static final String AGENT_ROLE = "RobotAgent";
    public static final String EXECUTOR_ROLE = "RobotExecutor";

    /** Pengguna: bentuk yang dipakai sejak sebelum Robot Agent ada. */
    public OpenOrchestratorPrincipal(UUID userId, UUID tenantId, String username, String role) {
        this(userId, tenantId, username, role, Kind.USER, null, null, null, null, null, null);
    }

    /** @param keyId penanda machine key yang dipakai masuk; kunci yang diganti atau dicabut memutus tokennya */
    public static OpenOrchestratorPrincipal agent(UUID machineId, UUID tenantId, String machineName, String keyId) {
        return new OpenOrchestratorPrincipal(machineId, tenantId, machineName, AGENT_ROLE, Kind.AGENT,
                machineId, null, null, null, keyId, null);
    }

    public static OpenOrchestratorPrincipal executor(UUID robotId, UUID tenantId, String robotName, UUID jobId,
                                                     UUID folderId) {
        return new OpenOrchestratorPrincipal(robotId, tenantId, robotName, EXECUTOR_ROLE, Kind.EXECUTOR,
                null, robotId, jobId, folderId, null, null);
    }

    /** @param sessionId sambungan Open Assistant yang menerbitkan tokennya; dicabut, tokennya ikut berhenti */
    public static OpenOrchestratorPrincipal assistant(UUID userId, UUID tenantId, String username, String role,
                                                      UUID sessionId) {
        return new OpenOrchestratorPrincipal(userId, tenantId, username, role, Kind.USER,
                null, null, null, null, null, sessionId);
    }

    public boolean isUser() {
        return kind == Kind.USER;
    }

    public boolean isAgent() {
        return kind == Kind.AGENT;
    }

    public boolean isExecutor() {
        return kind == Kind.EXECUTOR;
    }

    /** Pengguna yang memanggil lewat Open Assistant, bukan lewat dasbor. */
    public boolean isAssistant() {
        return assistantSessionId != null;
    }
}
