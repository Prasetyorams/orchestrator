package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.model.MachineStates;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pendaftaran mesin ke folder (tabel {@code folder_machines}, V12).
 *
 * <p>Mesin dan folder tetap entitas terpisah; yang menghubungkan keduanya
 * hanya baris di sini. Satu mesin boleh terdaftar di beberapa folder, dan
 * mengeluarkannya dari folder tidak menghapus mesinnya.
 */
@Repository
@RequiredArgsConstructor
public class FolderMachineRepository {

    private final Database database;

    /**
     * Kolom yang sama untuk daftar mesin folder dan mesin yang bisa
     * ditambahkan: status, nama komputer, slot, dan berapa robot FOLDER ITU
     * yang bekerja di mesin tersebut. {@code %1$s} = id folder (parameter).
     */
    private static String columns(long agentOnlineSeconds, long robotOnlineSeconds) {
        return """
                m.id, m.name, %s AS hostname, m.type, m.state, %s AS status, m.slots,
                (SELECT count(*) FROM robots r
                   JOIN folder_robots fr ON fr.robot_id = r.id AND fr.folder_id = ?
                  WHERE r.tenant_id = m.tenant_id
                    AND (r.machine_id = m.id OR (r.machine_id IS NULL AND r.machine_name = m.name))) AS folder_robots
                """.formatted(MachineStates.hostnameSql("m"),
                MachineStates.statusSql("m", agentOnlineSeconds, robotOnlineSeconds));
    }

    /** Mesin yang terdaftar di folder itu, urut nama. */
    public List<Map<String, Object>> findForFolder(UUID tenantId, UUID folderId, long agentOnlineSeconds,
                                                   long robotOnlineSeconds) {
        return database.queryRows("""
                SELECT %s, fm.created_at AS assigned_at, fm.created_by AS assigned_by
                  FROM folder_machines fm
                  JOIN machines m ON m.id = fm.machine_id
                 WHERE fm.tenant_id = ? AND fm.folder_id = ?
                 ORDER BY lower(m.name)
                """.formatted(columns(agentOnlineSeconds, robotOnlineSeconds)), folderId, tenantId, folderId);
    }

    /**
     * Mesin yang BELUM terdaftar di folder itu dan masih dipakai (bukan
     * Disabled). Yang dipakai robot folder itu lebih dulu — itu yang paling
     * mungkin sedang dicari.
     *
     * @param ownerUsername bukan null: hanya mesin tempat robot milik orang itu
     *                      bekerja — untuk pemilik Folder Saya yang bukan
     *                      pengelola folder
     */
    public List<Map<String, Object>> findAvailable(UUID tenantId, UUID folderId, String ownerUsername,
                                                   long agentOnlineSeconds, long robotOnlineSeconds) {
        List<Object> args = new ArrayList<>(List.of(folderId, tenantId, folderId));
        String ownerFilter = "";

        if (ownerUsername != null) {
            ownerFilter = " AND " + ownedBy("m");
            args.add(ownerUsername);
        }

        return database.queryRows("""
                SELECT %s
                  FROM machines m
                 WHERE m.tenant_id = ? AND m.state <> 'Disabled'
                   AND NOT EXISTS (SELECT 1 FROM folder_machines fm WHERE fm.folder_id = ? AND fm.machine_id = m.id)%s
                 ORDER BY folder_robots DESC, lower(m.name)
                """.formatted(columns(agentOnlineSeconds, robotOnlineSeconds), ownerFilter), args.toArray());
    }

    /** Mesin penyewa itu: nama dan keadaannya. */
    public Optional<Map<String, Object>> findMachine(UUID tenantId, UUID machineId) {
        return database.queryRow("SELECT id, name, state FROM machines WHERE tenant_id = ? AND id = ?",
                tenantId, machineId);
    }

    /** Ada robot milik orang itu yang bekerja di mesin tersebut. */
    public boolean isUsedBy(UUID tenantId, UUID machineId, String username) {
        return database.exists("SELECT count(*) FROM machines m WHERE m.tenant_id = ? AND m.id = ? AND " + ownedBy("m"),
                tenantId, machineId, username);
    }

    /** Mesin bernama itu terdaftar di folder tersebut. */
    public boolean isAssignedByName(UUID tenantId, UUID folderId, String machineName) {
        return database.exists("""
                SELECT count(*) FROM folder_machines fm JOIN machines m ON m.id = fm.machine_id
                 WHERE fm.tenant_id = ? AND fm.folder_id = ? AND m.name = ?
                """, tenantId, folderId, machineName);
    }

    /** @return 1 kalau baru didaftarkan, 0 kalau sudah terdaftar */
    public int assign(UUID tenantId, UUID folderId, UUID machineId, String username) {
        return database.update("""
                INSERT INTO folder_machines (folder_id, machine_id, tenant_id, created_at, created_by)
                VALUES (?, ?, ?, now(), ?)
                ON CONFLICT DO NOTHING
                """, folderId, machineId, tenantId, username);
    }

    public int unassign(UUID tenantId, UUID folderId, UUID machineId) {
        return database.update("DELETE FROM folder_machines WHERE tenant_id = ? AND folder_id = ? AND machine_id = ?",
                tenantId, folderId, machineId);
    }

    /**
     * Mesin tempat robot itu bekerja, dan apakah mesin itu terdaftar di folder
     * tersebut. Kosong kalau robotnya, atau mesinnya, belum dikenal.
     */
    public Optional<Map<String, Object>> findRobotMachine(UUID tenantId, UUID folderId, String robotName) {
        return database.queryRow("""
                SELECT m.name,
                       EXISTS (SELECT 1 FROM folder_machines fm
                                WHERE fm.folder_id = ? AND fm.machine_id = m.id) AS registered
                  FROM robots r
                  JOIN machines m ON m.tenant_id = r.tenant_id
                                 AND (m.id = r.machine_id OR (r.machine_id IS NULL AND m.name = r.machine_name))
                 WHERE r.tenant_id = ? AND r.name = ?
                 LIMIT 1
                """, folderId, tenantId, robotName);
    }

    /** Robot milik {@code ?} (nama pengguna) bekerja di mesin beralias {@code alias}. */
    private static String ownedBy(String alias) {
        return """
                EXISTS (SELECT 1 FROM robots o
                         WHERE o.tenant_id = %1$s.tenant_id AND o.username = ?
                           AND (o.machine_id = %1$s.id OR (o.machine_id IS NULL AND o.machine_name = %1$s.name)))
                """.formatted(alias);
    }
}
