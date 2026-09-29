package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Akses data robot.
 *
 * <p>Status DIHITUNG saat dibaca, bukan diambil dari kolomnya. Robot yang mati
 * tidak sempat memberi tahu bahwa ia mati — itulah artinya mati. Kolom status
 * yang hanya diperbarui oleh robotnya sendiri akan selamanya berbunyi AVAILABLE
 * untuk mesin yang sudah dimatikan seminggu lalu.
 *
 * <p>Kata sandi Windows robot TIDAK PERNAH ikut di kueri daftar: yang dibaca
 * dasbor hanyalah apakah sandinya ada ({@code has_windows_password}).
 */
@Repository
public class RobotRepository {

    private final Database database;

    /** Setelah berapa detik tanpa denyut sebuah robot v1 dianggap putus ({@code openorchestrator.robot.heartbeat-timeout}). */
    private final long heartbeatTimeoutSeconds;

    /** Setelah berapa detik tanpa denyut sebuah robot unattended dianggap Offline ({@code openorchestrator.agent.offline-after}). */
    private final long agentOfflineSeconds;

    /** Ambang putus sebuah baris robot: robot yang terikat ke mesin didenyut Robot Agent. */
    private final String thresholdExpression;

    /** Kolom status yang dihitung dari waktu denyut terakhir. */
    private final String statusColumn;

    /** Kolom yang dibaca dasbor — tanpa windows_password. */
    private final String detailColumns;

    /**
     * Konstruktor tertulis, bukan Lombok: ambang putusnya dibaca sekali dari
     * setelan dan dirangkai ke potongan SQL. Nilainya bilangan dari setelan,
     * bukan masukan pemakai, jadi aman ditempel ke kueri.
     */
    public RobotRepository(Database database, OpenOrchestratorProperties properties) {
        this.database = database;
        this.heartbeatTimeoutSeconds = properties.robot().heartbeatTimeout().toSeconds();
        this.agentOfflineSeconds = properties.agent().offlineAfter().toSeconds();
        this.thresholdExpression = "CASE WHEN machine_id IS NOT NULL THEN %d ELSE %d END"
                .formatted(agentOfflineSeconds, heartbeatTimeoutSeconds);
        this.statusColumn = """
                CASE
                    WHEN last_heartbeat_at IS NULL THEN 'DISCONNECTED'
                    WHEN now() - last_heartbeat_at > make_interval(secs => %s) THEN 'DISCONNECTED'
                    ELSE status
                END AS status
                """.formatted(thresholdExpression);
        this.detailColumns = """
                id, name, machine_name, username, type, environment, description,
                %s, cpu_percent, memory_mb, last_heartbeat_at, created_at,
                machine_id, windows_username, windows_password IS NOT NULL AS has_windows_password,
                windows_password_local, session_policy, agent_state, session_id, session_state, session_ready,
                reason_code, reason_text, executor_state, executor_pid, needs_attention,
                (SELECT j.id FROM jobs j WHERE j.robot_id = robots.id AND j.contract_version = 2
                    AND j.state IN %s ORDER BY j.started_at DESC LIMIT 1) AS current_job_id,
                (SELECT j.process_name FROM jobs j WHERE j.robot_id = robots.id AND j.contract_version = 2
                    AND j.state IN %s ORDER BY j.started_at DESC LIMIT 1) AS current_job_process,
                (SELECT j.state FROM jobs j WHERE j.robot_id = robots.id AND j.contract_version = 2
                    AND j.state IN %s ORDER BY j.started_at DESC LIMIT 1) AS current_job_state
                """.formatted(statusColumn, JobRepository.HELD_STATES, JobRepository.HELD_STATES,
                JobRepository.HELD_STATES);
    }

    /**
     * @param folderId null berarti semua robot penyewa; selain itu hanya robot
     *                 yang ditugaskan ke folder itu.
     *
     * <p>{@code folders} berisi nama folder bersama tempat robotnya ditugaskan,
     * supaya halaman robot penyewa bisa menunjukkannya tanpa bertanya satu per
     * satu. Folder Saya tidak ikut disebut: namanya sama untuk semua orang dan
     * tidak menjelaskan apa pun.
     */
    public List<Map<String, Object>> findAll(UUID tenantId, UUID folderId) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String folderFilter = "";

        if (folderId != null) {
            folderFilter = " AND id IN (SELECT robot_id FROM folder_robots WHERE tenant_id = ? AND folder_id = ?)";
            args.add(tenantId);
            args.add(folderId);
        }

        return database.queryRows("""
                SELECT %s,
                       (SELECT array_agg(f.name ORDER BY lower(f.name))
                          FROM folder_robots fr JOIN folders f ON f.id = fr.folder_id
                         WHERE fr.robot_id = robots.id AND f.owner_id IS NULL) AS folders
                  FROM robots
                 WHERE tenant_id = ?%s
                 ORDER BY name
                """.formatted(detailColumns, folderFilter), args.toArray());
    }

    public Optional<Map<String, Object>> findByName(UUID tenantId, String name) {
        return database.queryRow("SELECT %s FROM robots WHERE tenant_id = ? AND name = ?".formatted(detailColumns),
                tenantId, name);
    }

    public Optional<UUID> findIdByName(UUID tenantId, String name) {
        return database.queryScalar("SELECT id FROM robots WHERE tenant_id = ? AND name = ?", tenantId, name)
                .map(Uuids::fromColumn);
    }

    /** Untuk dasbor: ikut membawa jumlah pekerjaan yang sedang dijalankannya. */
    public List<Map<String, Object>> findForDashboard(UUID tenantId, UUID folderId, int limit) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String folderFilter = "";

        if (folderId != null) {
            folderFilter = " AND r.id IN (SELECT robot_id FROM folder_robots WHERE tenant_id = ? AND folder_id = ?)";
            args.add(tenantId);
            args.add(folderId);
        }

        args.add(limit);

        String threshold = thresholdExpression.replace("machine_id", "r.machine_id");

        return database.queryRows("""
                SELECT r.name, r.machine_name, r.type, r.environment, r.cpu_percent, r.memory_mb,
                       r.last_heartbeat_at,
                       CASE WHEN r.last_heartbeat_at IS NULL
                              OR now() - r.last_heartbeat_at > make_interval(secs => %1$s)
                            THEN 'DISCONNECTED' ELSE r.status END AS status,
                       (SELECT count(*) FROM jobs j
                         WHERE j.tenant_id = r.tenant_id AND j.robot_name = r.name
                           AND j.state IN %3$s) AS running_jobs
                  FROM robots r
                 WHERE r.tenant_id = ?%2$s
                 ORDER BY CASE WHEN r.last_heartbeat_at IS NULL
                                 OR now() - r.last_heartbeat_at > make_interval(secs => %1$s)
                               THEN 2 ELSE 0 END,
                          r.name
                 LIMIT ?
                """.formatted(threshold, folderFilter, JobRepository.HELD_STATES), args.toArray());
    }

    public boolean existsByName(UUID tenantId, String name) {
        return database.exists("SELECT count(*) FROM robots WHERE tenant_id = ? AND name = ?", tenantId, name);
    }

    public void registerFromHeartbeat(UUID tenantId, String name, String machineName,
                                      String status, double cpuPercent, double memoryMb) {
        database.update("""
                INSERT INTO robots
                    (id, tenant_id, name, machine_name, type, environment, description,
                     status, cpu_percent, memory_mb, last_heartbeat_at, created_at)
                VALUES (?, ?, ?, ?, 'Attended', 'Production',
                        'Terdaftar sendiri saat denyut pertama.', ?, ?, ?, now(), now())
                """, UUID.randomUUID(), tenantId, name, machineName, status, cpuPercent, memoryMb);
    }

    /**
     * COALESCE pada machine_name: denyut yang tidak menyebut nama mesin tidak
     * boleh MENGHAPUS nama yang sudah diketahui.
     */
    public void recordHeartbeat(UUID tenantId, String name, String machineName,
                                String status, double cpuPercent, double memoryMb) {
        database.update("""
                UPDATE robots
                   SET status = ?, cpu_percent = ?, memory_mb = ?,
                       last_heartbeat_at = now(),
                       machine_name = COALESCE(?, machine_name)
                 WHERE tenant_id = ? AND name = ?
                """, status, cpuPercent, memoryMb, machineName, tenantId, name);
    }

    /**
     * @param machineId mesin robot unattended; null untuk robot attended
     * @param windowsPassword sudah disandikan SecretBox, atau null
     */
    public void insert(UUID tenantId, String name, String machineName, String username, String type,
                       String environment, String description, UUID machineId, String windowsUsername,
                       String windowsPassword, boolean windowsPasswordLocal, String sessionPolicy) {
        database.update("""
                INSERT INTO robots
                    (id, tenant_id, name, machine_name, username, type, environment, description,
                     status, cpu_percent, memory_mb, last_heartbeat_at, created_at,
                     machine_id, windows_username, windows_password, windows_password_local, session_policy)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DISCONNECTED', 0, 0, NULL, now(), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, name, machineName, username, type, environment, description,
                machineId, windowsUsername, windowsPassword, windowsPasswordLocal, sessionPolicy);
    }

    /** Baris lengkap untuk pengubahan, termasuk mesin lamanya. Tanpa sandinya. */
    public Optional<Map<String, Object>> findForUpdate(UUID tenantId, String name) {
        return database.queryRow("""
                SELECT id, name, machine_id, machine_name, type, environment, description, windows_username,
                       windows_password_local, session_policy
                  FROM robots WHERE tenant_id = ? AND name = ?
                """, tenantId, name);
    }

    /**
     * Ubah setelan robot.
     *
     * @param windowsPassword sandi baru yang sudah disandikan, atau null untuk
     *                        mempertahankan yang lama
     * @param clearPassword   hapus sandi yang tersimpan (sandinya pindah ke mesin robot)
     */
    public void updateConfig(UUID tenantId, UUID robotId, String type, String environment, String description,
                             UUID machineId, String machineName, String windowsUsername, String windowsPassword,
                             boolean clearPassword, boolean windowsPasswordLocal, String sessionPolicy) {
        database.update("""
                UPDATE robots
                   SET type = ?, environment = ?, description = ?, machine_id = ?, machine_name = ?,
                       windows_username = ?,
                       windows_password = CASE WHEN ? THEN NULL ELSE COALESCE(?, windows_password) END,
                       windows_password_local = ?,
                       session_policy = ?,
                       needs_attention = CASE WHEN ? THEN NULL ELSE needs_attention END
                 WHERE tenant_id = ? AND id = ?
                """, type, environment, description, machineId, machineName, windowsUsername, clearPassword,
                windowsPassword, windowsPasswordLocal, sessionPolicy, windowsPassword != null || clearPassword,
                tenantId, robotId);
    }

    public int deleteByName(UUID tenantId, String name) {
        return database.update("DELETE FROM robots WHERE tenant_id = ? AND name = ?", tenantId, name);
    }

    /** Hitungan per keadaan, diambil dalam satu kueri supaya angkanya sezaman. */
    public Map<String, Object> countByStatus(UUID tenantId, UUID folderId) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String folderFilter = "";

        if (folderId != null) {
            folderFilter = " AND id IN (SELECT robot_id FROM folder_robots WHERE tenant_id = ? AND folder_id = ?)";
            args.add(tenantId);
            args.add(folderId);
        }

        return database.queryRow("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'AVAILABLE' AND is_connected) AS available,
                       count(*) FILTER (WHERE status = 'BUSY'      AND is_connected) AS busy,
                       count(*) FILTER (WHERE NOT is_connected)                      AS disconnected
                  FROM (SELECT status,
                               last_heartbeat_at IS NOT NULL
                               AND now() - last_heartbeat_at <= make_interval(secs => %s) AS is_connected
                          FROM robots WHERE tenant_id = ?%s) AS r
                """.formatted(thresholdExpression, folderFilter), args.toArray())
                .orElseGet(LinkedHashMap::new);
    }

    /** Jumlah robot Attended, atau selain Attended. Dipakai halaman lisensi. */
    public long countByAttendance(UUID tenantId, boolean attended) {
        String comparison = attended ? "=" : "<>";

        return database.count("SELECT count(*) FROM robots WHERE tenant_id = ? AND type " + comparison + " 'Attended'",
                tenantId);
    }

    // -----------------------------------------------------------------
    // Robot Agent
    // -----------------------------------------------------------------

    /** Robot yang dilayani agent mesin ini: yang diikat ke mesinnya lewat dasbor. */
    public List<Map<String, Object>> findForMachine(UUID machineId) {
        return database.queryRows("""
                SELECT id, name, windows_username, session_policy, windows_password_local
                  FROM robots WHERE machine_id = ?
                 ORDER BY name
                """, machineId);
    }

    /**
     * Robot milik mesin ini, DIKUNCI sampai transaksi selesai — dua klaim
     * bersamaan untuk robot yang sama tidak boleh sama-sama lolos.
     */
    public Optional<Map<String, Object>> lockForMachine(UUID robotId, UUID machineId) {
        return database.queryRow("""
                SELECT id, tenant_id, name, agent_state, session_ready, windows_username, session_policy,
                       windows_password_local, needs_attention
                  FROM robots WHERE id = ? AND machine_id = ?
                 FOR UPDATE
                """, robotId, machineId);
    }

    public Optional<Map<String, Object>> findForMachine(UUID robotId, UUID machineId) {
        return database.queryRow("SELECT id, tenant_id, name FROM robots WHERE id = ? AND machine_id = ?",
                robotId, machineId);
    }

    /** Keadaan satu robot dari denyut agent. Menghitung sebagai denyut robot itu sendiri. */
    public int recordAgentReport(UUID robotId, UUID machineId, String status, String agentState, Integer sessionId,
                                 String sessionState, Boolean sessionReady, String reasonCode, String reasonText,
                                 String executorState, Integer executorPid, Double cpuPercent, Double memoryMb) {
        return database.update("""
                UPDATE robots
                   SET status = ?, agent_state = ?, session_id = ?, session_state = ?, session_ready = ?,
                       reason_code = ?, reason_text = ?, executor_state = ?, executor_pid = ?,
                       cpu_percent = COALESCE(?, cpu_percent), memory_mb = COALESCE(?, memory_mb),
                       last_heartbeat_at = now()
                 WHERE id = ? AND machine_id = ?
                """, status, agentState, sessionId, sessionState, sessionReady, reasonCode, reasonText, executorState,
                executorPid, cpuPercent, memoryMb, robotId, machineId);
    }

    /** Akun Windows robot, termasuk sandinya yang MASIH TERSANDI. Hanya untuk jawaban windows-credential. */
    public Optional<Map<String, Object>> findWindowsAccount(UUID robotId) {
        return database.queryRow("""
                SELECT name, windows_username, windows_password, windows_password_local
                  FROM robots WHERE id = ?
                """, robotId);
    }

    /** Tanda "Perlu perhatian", mis. LogonFailed — hilang saat sandinya diganti. */
    public void setNeedsAttention(UUID robotId, String code) {
        database.update("UPDATE robots SET needs_attention = ? WHERE id = ?", code, robotId);
    }

    /**
     * Sesudah laporan akhir: robot langsung Idle di server, tanpa menunggu
     * denyut berikutnya — kecuali ia masih memegang job lain.
     */
    public void markIdleIfFree(UUID robotId) {
        database.update("""
                UPDATE robots
                   SET agent_state = 'Idle', status = 'AVAILABLE'
                 WHERE id = ? AND agent_state IS DISTINCT FROM 'Error'
                   AND NOT EXISTS (SELECT 1 FROM jobs j WHERE j.robot_id = robots.id AND j.contract_version = 2
                                     AND j.state IN %s)
                """.formatted(JobRepository.HELD_STATES), robotId);
    }

    /** Mesin tempat robot-robot ini diikat, untuk menaikkan versi setelannya. */
    public List<UUID> findMachineIdsByName(UUID tenantId, String name) {
        return database.query("SELECT machine_id FROM robots WHERE tenant_id = ? AND name = ? AND machine_id IS NOT NULL",
                (rs, rowNumber) -> rs.getObject(1, UUID.class), tenantId, name);
    }
}
