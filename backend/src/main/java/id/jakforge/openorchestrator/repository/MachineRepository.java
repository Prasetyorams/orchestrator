package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.model.MachineStates;
import id.jakforge.openorchestrator.model.RuntimeTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mesin tempat robot berjalan, beserta machine key dan keadaan Robot Agent-nya.
 *
 * <p>{@code key_hash} tidak pernah dipilih di kueri yang dibaca dasbor; yang
 * terlihat hanya awalan kuncinya untuk mengenali kunci mana yang terpasang.
 */
@Repository
@RequiredArgsConstructor
public class MachineRepository {

    private final Database database;

    /**
     * @param offlineSeconds      tanpa denyut agent selama ini: Offline
     * @param robotOnlineSeconds  denyut robot v1 selama ini masih berarti tersambung (untuk {@code status})
     */
    public List<Map<String, Object>> findAll(UUID tenantId, long offlineSeconds, long robotOnlineSeconds) {
        return database.queryRows("""
                SELECT m.id, m.name, m.type, m.license_key, m.description, m.created_at,
                       m.slots, m.lease_seconds, m.key_hash IS NOT NULL AS has_key, m.key_prefix, m.key_created_at,
                       m.agent_version, m.agent_os, m.agent_host_name, m.max_interactive_sessions,
                       m.agent_cpu_percent, m.agent_memory_used_mb, m.agent_memory_total_mb,
                       m.last_agent_login_at, m.last_agent_heartbeat_at,
                       (m.last_agent_heartbeat_at IS NOT NULL
                        AND now() - m.last_agent_heartbeat_at <= make_interval(secs => ?)) AS agent_online,
                       (SELECT count(*) FROM robots r
                         WHERE r.tenant_id = m.tenant_id AND r.machine_name = m.name) AS robot_count,
                       (SELECT count(*) FROM robots r WHERE r.machine_id = m.id) AS unattended_robot_count,
                       (SELECT count(*) FROM jobs j WHERE j.machine_id = m.id AND j.contract_version = 2
                           AND j.state IN %s) AS active_jobs,
                       m.state, %s AS status,
                       (SELECT array_agg(f.name ORDER BY lower(f.name))
                          FROM folder_machines fm JOIN folders f ON f.id = fm.folder_id
                         WHERE fm.machine_id = m.id AND f.owner_id IS NULL) AS folders
                  FROM machines m
                 WHERE m.tenant_id = ?
                 ORDER BY m.name
                """.formatted(JobRepository.HELD_STATES,
                MachineStates.statusSql("m", offlineSeconds, robotOnlineSeconds)), offlineSeconds, tenantId);
    }

    public Optional<String> findNameById(UUID tenantId, UUID machineId) {
        return database.queryScalar("SELECT name FROM machines WHERE tenant_id = ? AND id = ?", tenantId, machineId)
                .map(Object::toString);
    }

    public boolean existsByName(UUID tenantId, String name) {
        return database.exists("SELECT count(*) FROM machines WHERE tenant_id = ? AND name = ?", tenantId, name);
    }

    public Optional<Map<String, Object>> findByName(UUID tenantId, String name) {
        return database.queryRow("SELECT id, name, slots, lease_seconds FROM machines WHERE tenant_id = ? AND name = ?",
                tenantId, name);
    }

    /**
     * Mesin baru selalu lahir dengan satu runtime Production: mesin yang
     * didaftarkan denyut robot atau saat OpenOrchestrator naik harus langsung
     * bisa menjalankan job, seperti sebelum tipe runtime ada.
     *
     * @return id mesin barunya
     */
    public UUID insert(UUID tenantId, String name, String type, String licenseKey, String description) {
        UUID id = UUID.randomUUID();

        database.update("""
                INSERT INTO machines (id, tenant_id, name, type, license_key, description, created_at, slots)
                VALUES (?, ?, ?, ?, ?, ?, now(), 1)
                """, id, tenantId, name, type, licenseKey, description);
        database.update("INSERT INTO machine_runtimes (machine_id, runtime_type, slots) VALUES (?, ?, 1)",
                id, RuntimeTypes.PRODUCTION);

        return id;
    }

    public int deleteByName(UUID tenantId, String name) {
        return database.update("DELETE FROM machines WHERE tenant_id = ? AND name = ?", tenantId, name);
    }

    /**
     * Ubah setelan mesin; yang null tidak diubah. Versi setelannya naik supaya
     * agent mengambil yang baru. Jumlah slot tidak diubah di sini — slot
     * adalah jumlah runtime, lihat {@link #replaceRuntimes}.
     *
     * @param state Active, Maintenance, atau Disabled ({@link MachineStates}); null tidak diubah
     */
    public int updateSettings(UUID tenantId, String name, String type, String description, Integer leaseSeconds,
                              String state) {
        return database.update("""
                UPDATE machines
                   SET type = COALESCE(?, type),
                       description = COALESCE(?, description),
                       lease_seconds = COALESCE(?, lease_seconds),
                       state = COALESCE(?, state),
                       settings_version = settings_version + 1
                 WHERE tenant_id = ? AND name = ?
                """, type, description, leaseSeconds, state, tenantId, name);
    }

    // -----------------------------------------------------------------
    // Runtime
    // -----------------------------------------------------------------

    /** Runtime satu mesin: tipe → jumlah, urutan katalog. Tipe tanpa runtime tidak disebut. */
    public Map<String, Integer> findRuntimes(UUID machineId) {
        Map<String, Integer> runtimes = new LinkedHashMap<>();

        for (Map<String, Object> row : database.queryRows("""
                SELECT runtime_type, slots FROM machine_runtimes WHERE machine_id = ? ORDER BY %s
                """.formatted(RuntimeTypes.sqlOrder("runtime_type")), machineId)) {
            runtimes.put((String) row.get("runtimeType"), ((Number) row.get("slots")).intValue());
        }

        return runtimes;
    }

    /** Runtime semua mesin penyewa: id mesin → (tipe → jumlah). */
    public Map<String, Map<String, Integer>> findRuntimesForTenant(UUID tenantId) {
        Map<String, Map<String, Integer>> byMachine = new LinkedHashMap<>();

        for (Map<String, Object> row : database.queryRows("""
                SELECT r.machine_id, r.runtime_type, r.slots
                  FROM machine_runtimes r
                  JOIN machines m ON m.id = r.machine_id
                 WHERE m.tenant_id = ?
                 ORDER BY %s
                """.formatted(RuntimeTypes.sqlOrder("r.runtime_type")), tenantId)) {
            byMachine.computeIfAbsent((String) row.get("machineId"), id -> new LinkedHashMap<>())
                    .put((String) row.get("runtimeType"), ((Number) row.get("slots")).intValue());
        }

        return byMachine;
    }

    /**
     * Ganti seluruh runtime mesin. {@code slots} mesin menjadi jumlah
     * runtimenya — itu batas job bersamaan yang dibaca Robot Agent — dan versi
     * setelannya naik supaya agent mengambil yang baru.
     *
     * @param runtimes tipe baku → jumlah; yang nol tidak disimpan
     */
    public void replaceRuntimes(UUID machineId, Map<String, Integer> runtimes) {
        database.update("DELETE FROM machine_runtimes WHERE machine_id = ?", machineId);

        int total = 0;

        for (Map.Entry<String, Integer> runtime : runtimes.entrySet()) {
            if (runtime.getValue() == null || runtime.getValue() <= 0) continue;

            database.update("INSERT INTO machine_runtimes (machine_id, runtime_type, slots) VALUES (?, ?, ?)",
                    machineId, runtime.getKey(), runtime.getValue());
            total += runtime.getValue();
        }

        database.update("UPDATE machines SET slots = ?, settings_version = settings_version + 1 WHERE id = ?",
                total, machineId);
    }


    /** Pasang kunci baru; kunci lama langsung tidak berlaku. */
    public int setKey(UUID tenantId, String name, String keyHash, String keyPrefix) {
        return database.update("""
                UPDATE machines
                   SET key_hash = ?, key_prefix = ?, key_created_at = now(), settings_version = settings_version + 1
                 WHERE tenant_id = ? AND name = ?
                """, keyHash, keyPrefix, tenantId, name);
    }

    public int clearKey(UUID tenantId, String name) {
        return database.update("""
                UPDATE machines
                   SET key_hash = NULL, key_prefix = NULL, key_created_at = NULL,
                       settings_version = settings_version + 1
                 WHERE tenant_id = ? AND name = ? AND key_hash IS NOT NULL
                """, tenantId, name);
    }

    /** Pencarian lintas tenant: agent masuk hanya dengan kuncinya. */
    public Optional<Map<String, Object>> findByKeyHash(String keyHash) {
        return database.queryRow("""
                SELECT id, tenant_id, name, slots, lease_seconds, settings_version, max_interactive_sessions,
                       agent_host_name
                  FROM machines WHERE key_hash = ?
                """, keyHash);
    }

    public Optional<Map<String, Object>> findById(UUID machineId) {
        return database.queryRow("""
                SELECT id, tenant_id, name, slots, lease_seconds, settings_version, max_interactive_sessions,
                       key_hash IS NOT NULL AS has_key
                  FROM machines WHERE id = ?
                """, machineId);
    }

    /** Dikunci: klaim job memeriksa slot mesin, dan dua klaim bersamaan tidak boleh sama-sama lolos. */
    public Optional<Map<String, Object>> lockById(UUID machineId) {
        return database.queryRow("""
                SELECT id, tenant_id, name, slots, lease_seconds, max_interactive_sessions
                  FROM machines WHERE id = ? FOR UPDATE
                """, machineId);
    }

    public void recordAgentLogin(UUID machineId, String agentVersion, String os, String hostName,
                                 Integer maxInteractiveSessions) {
        database.update("""
                UPDATE machines
                   SET agent_version = ?, agent_os = COALESCE(?, agent_os), agent_host_name = COALESCE(?, agent_host_name),
                       max_interactive_sessions = COALESCE(?, max_interactive_sessions),
                       last_agent_login_at = now()
                 WHERE id = ?
                """, agentVersion, os, hostName, maxInteractiveSessions, machineId);
    }

    public void recordAgentHeartbeat(UUID machineId, String agentVersion, Double cpuPercent, Double memoryUsedMb,
                                     Double memoryTotalMb, Integer maxInteractiveSessions) {
        database.update("""
                UPDATE machines
                   SET agent_version = COALESCE(?, agent_version),
                       agent_cpu_percent = ?, agent_memory_used_mb = ?, agent_memory_total_mb = ?,
                       max_interactive_sessions = COALESCE(?, max_interactive_sessions),
                       last_agent_heartbeat_at = now()
                 WHERE id = ?
                """, agentVersion, cpuPercent, memoryUsedMb, memoryTotalMb, maxInteractiveSessions, machineId);
    }

    /** Hash kunci yang terpasang sekarang, untuk mencocokkan token agent. */
    public Optional<String> findKeyHash(UUID machineId) {
        return database.queryScalar("SELECT key_hash FROM machines WHERE id = ?", machineId).map(String::valueOf);
    }

    public Optional<Integer> findSettingsVersion(UUID machineId) {
        return database.queryScalar("SELECT settings_version FROM machines WHERE id = ?", machineId)
                .map(value -> ((Number) value).intValue());
    }

    public void bumpSettingsVersion(UUID machineId) {
        database.update("UPDATE machines SET settings_version = settings_version + 1 WHERE id = ?", machineId);
    }
}
