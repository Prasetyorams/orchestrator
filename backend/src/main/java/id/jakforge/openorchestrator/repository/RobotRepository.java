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
 */
@Repository
public class RobotRepository {

    private final Database database;

    /** Setelah berapa detik tanpa denyut sebuah robot dianggap putus ({@code openorchestrator.robot.heartbeat-timeout}). */
    private final long heartbeatTimeoutSeconds;

    /** Kolom status yang dihitung dari waktu denyut terakhir. */
    private final String statusColumn;

    /**
     * Konstruktor tertulis, bukan Lombok: ambang putusnya dibaca sekali dari
     * setelan dan dirangkai ke potongan SQL. Nilainya bilangan dari setelan,
     * bukan masukan pemakai, jadi aman ditempel ke kueri.
     */
    public RobotRepository(Database database, OpenOrchestratorProperties properties) {
        this.database = database;
        this.heartbeatTimeoutSeconds = properties.robot().heartbeatTimeout().toSeconds();
        this.statusColumn = """
                CASE
                    WHEN last_heartbeat_at IS NULL THEN 'DISCONNECTED'
                    WHEN now() - last_heartbeat_at > make_interval(secs => %d) THEN 'DISCONNECTED'
                    ELSE status
                END AS status
                """.formatted(heartbeatTimeoutSeconds);
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
                SELECT id, name, machine_name, username, type, environment, description,
                       %s, cpu_percent, memory_mb, last_heartbeat_at, created_at,
                       (SELECT array_agg(f.name ORDER BY lower(f.name))
                          FROM folder_robots fr JOIN folders f ON f.id = fr.folder_id
                         WHERE fr.robot_id = robots.id AND f.owner_id IS NULL) AS folders
                  FROM robots
                 WHERE tenant_id = ?%s
                 ORDER BY name
                """.formatted(statusColumn, folderFilter), args.toArray());
    }

    public Optional<Map<String, Object>> findByName(UUID tenantId, String name) {
        return database.queryRow("""
                SELECT id, name, machine_name, username, type, environment, description,
                       %s, cpu_percent, memory_mb, last_heartbeat_at, created_at
                  FROM robots
                 WHERE tenant_id = ? AND name = ?
                """.formatted(statusColumn), tenantId, name);
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

        return database.queryRows("""
                SELECT r.name, r.machine_name, r.type, r.environment, r.cpu_percent, r.memory_mb,
                       r.last_heartbeat_at,
                       CASE WHEN r.last_heartbeat_at IS NULL
                              OR now() - r.last_heartbeat_at > make_interval(secs => %1$d)
                            THEN 'DISCONNECTED' ELSE r.status END AS status,
                       (SELECT count(*) FROM jobs j
                         WHERE j.tenant_id = r.tenant_id AND j.robot_name = r.name
                           AND j.state = 'RUNNING') AS running_jobs
                  FROM robots r
                 WHERE r.tenant_id = ?%2$s
                 ORDER BY CASE WHEN r.last_heartbeat_at IS NULL
                                 OR now() - r.last_heartbeat_at > make_interval(secs => %1$d)
                               THEN 2 ELSE 0 END,
                          r.name
                 LIMIT ?
                """.formatted(heartbeatTimeoutSeconds, folderFilter), args.toArray());
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

    public void insert(UUID tenantId, String name, String machineName, String username,
                       String type, String environment, String description) {
        database.update("""
                INSERT INTO robots
                    (id, tenant_id, name, machine_name, username, type, environment, description,
                     status, cpu_percent, memory_mb, last_heartbeat_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DISCONNECTED', 0, 0, NULL, now())
                """, UUID.randomUUID(), tenantId, name, machineName, username, type, environment, description);
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
                               AND now() - last_heartbeat_at <= make_interval(secs => %d) AS is_connected
                          FROM robots WHERE tenant_id = ?%s) AS r
                """.formatted(heartbeatTimeoutSeconds, folderFilter), args.toArray()).orElseGet(LinkedHashMap::new);
    }

    /** Jumlah robot Attended, atau selain Attended. Dipakai halaman lisensi. */
    public long countByAttendance(UUID tenantId, boolean attended) {
        String comparison = attended ? "=" : "<>";

        return database.count("SELECT count(*) FROM robots WHERE tenant_id = ? AND type " + comparison + " 'Attended'",
                tenantId);
    }
}
