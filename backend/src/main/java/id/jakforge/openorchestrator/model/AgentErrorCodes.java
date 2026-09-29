package id.jakforge.openorchestrator.model;

import java.util.Set;

/**
 * Kode kegagalan job (kolom {@code jobs.error_code}), lihat ROBOT-API.md 2.4.
 *
 * <p>Sebagian dilaporkan agent, sebagian DISIMPULKAN Orchestrator. Kode yang
 * tidak ada di sini tetap disimpan apa adanya — agent yang lebih baru boleh
 * menambah kode — tetapi tidak pernah memicu percobaan ulang otomatis.
 */
public final class AgentErrorCodes {

    public static final String SESSION_PREPARATION_FAILED = "SessionPreparationFailed";
    public static final String LOGON_FAILED = "LogonFailed";
    public static final String EXECUTOR_START_FAILED = "ExecutorStartFailed";
    public static final String EXECUTOR_CRASHED = "ExecutorCrashed";
    public static final String PACKAGE_NOT_FOUND = "PackageNotFound";
    public static final String PACKAGE_DOWNLOAD_FAILED = "PackageDownloadFailed";
    public static final String PACKAGE_INTEGRITY_FAILED = "PackageIntegrityFailed";
    public static final String WORKFLOW_LOAD_FAILED = "WorkflowLoadFailed";
    public static final String WORKFLOW_FAILED = "WorkflowFailed";
    public static final String TIMEOUT = "Timeout";
    public static final String AGENT_SHUTDOWN = "AgentShutdown";

    /** Disimpulkan Orchestrator, tidak pernah dikirim agent. */
    public static final String AGENT_RESTARTED = "AgentRestarted";
    public static final String AGENT_LOST = "AgentLost";
    public static final String LEASE_EXPIRED = "LeaseExpired";

    /** Kode sebelum workflow sempat berjalan — hanya ini yang boleh diulang otomatis. */
    public static final Set<String> RETRYABLE = Set.of(
            SESSION_PREPARATION_FAILED, EXECUTOR_START_FAILED, EXECUTOR_CRASHED, PACKAGE_DOWNLOAD_FAILED,
            PACKAGE_INTEGRITY_FAILED, AGENT_RESTARTED, AGENT_LOST, LEASE_EXPIRED);

    /** Batas panjang kolom {@code jobs.error_code}. */
    public static final int MAX_LENGTH = 40;

    private AgentErrorCodes() {
    }
}
