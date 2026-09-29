package id.jakforge.openorchestrator.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

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

    /** @param offlineSeconds tanpa denyut agent selama ini: Offline */
    public List<Map<String, Object>> findAll(UUID tenantId, long offlineSeconds) {
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
                           AND j.state IN %s) AS active_jobs
                  FROM machines m
                 WHERE m.tenant_id = ?
                 ORDER BY m.name
                """.formatted(JobRepository.HELD_STATES), offlineSeconds, tenantId);
    }

    public boolean existsByName(UUID tenantId, String name) {
        return database.exists("SELECT count(*) FROM machines WHERE tenant_id = ? AND name = ?", tenantId, name);
    }

    public Optional<Map<String, Object>> findByName(UUID tenantId, String name) {
        return database.queryRow("SELECT id, name, slots, lease_seconds FROM machines WHERE tenant_id = ? AND name = ?",
                tenantId, name);
    }

    public void insert(UUID tenantId, String name, String type, String licenseKey, String description) {
        database.update("""
                INSERT INTO machines (id, tenant_id, name, type, license_key, description, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), tenantId, name, type, licenseKey, description);
    }

    public int deleteByName(UUID tenantId, String name) {
        return database.update("DELETE FROM machines WHERE tenant_id = ? AND name = ?", tenantId, name);
    }

    /**
     * Ubah setelan mesin; yang null tidak diubah. Versi setelannya naik supaya
     * agent mengambil yang baru.
     */
    public int updateSettings(UUID tenantId, String name, String type, String description, Integer slots,
                              Integer leaseSeconds) {
        return database.update("""
                UPDATE machines
                   SET type = COALESCE(?, type),
                       description = COALESCE(?, description),
                       slots = COALESCE(?, slots),
                       lease_seconds = COALESCE(?, lease_seconds),
                       settings_version = settings_version + 1
                 WHERE tenant_id = ? AND name = ?
                """, type, description, slots, leaseSeconds, tenantId, name);
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
