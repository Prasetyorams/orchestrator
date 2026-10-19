package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.model.TriggerDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Akses data pemicu terjadwal.
 *
 * <p>Nama pemicu unik PER FOLDER (V5), jadi setiap perubahan menyebut folder.
 */
@Repository
@RequiredArgsConstructor
public class TriggerRepository {

    private static final String COLUMNS = """
            id, name, process_name, robot_name, type, cron, interval_minutes,
            priority, timezone, runtime_type, enabled, next_run_at, last_run_at, created_at, folder_id,
            calendar_id, (SELECT c.name FROM calendars c WHERE c.id = triggers.calendar_id) AS calendar_name
            """;

    /** Jadwal sebuah pemicu: cukup untuk menghitung waktu jalan berikutnya. */
    public record TriggerSchedule(boolean enabled, String cron, int intervalMinutes, String timezone,
                                  UUID calendarId) {
    }

    /** Pemicu aktif yang memakai sebuah kalender, untuk dihitung ulang saat kalendernya berubah. */
    public record CalendarTrigger(UUID id, String cron, int intervalMinutes, String timezone,
                                  OffsetDateTime nextRunAt) {
    }

    /** Pemicu yang sudah waktunya jalan, beserta yang dibutuhkan untuk menjadwalkan pekerjaannya. */
    public record DueTrigger(UUID id, UUID tenantId, UUID folderId, String name, String processName,
                             String robotName, int intervalMinutes, String cron, String priority,
                             String timezone, UUID calendarId, OffsetDateTime nextRunAt) {
    }

    private final Database database;

    /** @param folderId null berarti seluruh penyewa. */
    public List<Map<String, Object>> findAll(UUID tenantId, UUID folderId) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String folderFilter = "";

        if (folderId != null) {
            folderFilter = " AND folder_id = ?";
            args.add(folderId);
        }

        return database.queryRows("""
                SELECT %s FROM triggers
                 WHERE tenant_id = ?%s
                 ORDER BY enabled DESC, next_run_at
                """.formatted(COLUMNS, folderFilter), args.toArray());
    }

    /** Untuk dasbor: hanya yang aktif, paling dekat lebih dulu. */
    public List<Map<String, Object>> findUpcoming(UUID tenantId, UUID folderId, int limit) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String folderFilter = "";

        if (folderId != null) {
            folderFilter = " AND folder_id = ?";
            args.add(folderId);
        }

        args.add(limit);

        return database.queryRows("""
                SELECT name, process_name, type, interval_minutes, cron, timezone,
                       enabled, next_run_at, last_run_at,
                       (SELECT c.name FROM calendars c WHERE c.id = triggers.calendar_id) AS calendar_name
                  FROM triggers
                 WHERE tenant_id = ? AND enabled%s
                 ORDER BY next_run_at
                 LIMIT ?
                """.formatted(folderFilter), args.toArray());
    }

    /**
     * Pemicu yang bisa jalan di mesin Robot Agent itu (GET /api/agent/triggers):
     * yang menargetkan salah satu robot mesin itu, atau yang tidak menargetkan
     * robot tertentu tetapi foldernya — folder prosesnya — adalah folder salah
     * satu robot mesin itu. Pemicu yang menargetkan robot lain tidak ikut,
     * walaupun prosesnya ada di folder yang sama. Keduanya hanya kalau mesin
     * itu terdaftar di folder pemicunya (V12): di folder lain job-nya tidak
     * akan diambil mesin ini.
     */
    public List<Map<String, Object>> findForMachine(UUID tenantId, UUID machineId) {
        return database.queryRows("""
                SELECT t.id, t.name, t.folder_id, f.name AS folder_name, t.process_name,
                       r.id AS robot_id, NULLIF(t.robot_name, '') AS robot_name,
                       t.type, t.cron, t.interval_minutes, t.timezone, t.priority,
                       t.enabled, t.next_run_at, t.last_run_at, t.calendar_id, c.name AS calendar_name
                  FROM triggers t
                  JOIN folders f ON f.id = t.folder_id
                  LEFT JOIN calendars c ON c.id = t.calendar_id
                  LEFT JOIN robots r ON r.tenant_id = t.tenant_id AND r.name = t.robot_name
                 WHERE t.tenant_id = ?
                   AND ((NULLIF(t.robot_name, '') IS NOT NULL AND r.machine_id = ?)
                        OR (NULLIF(t.robot_name, '') IS NULL AND t.folder_id IN (
                               SELECT fr.folder_id
                                 FROM folder_robots fr
                                 JOIN robots mr ON mr.id = fr.robot_id
                                WHERE mr.tenant_id = ? AND mr.machine_id = ?)))
                   AND EXISTS (SELECT 1 FROM folder_machines fm
                                WHERE fm.folder_id = t.folder_id AND fm.machine_id = ?)
                 ORDER BY t.enabled DESC, t.next_run_at NULLS LAST, lower(t.name)
                """, tenantId, machineId, tenantId, machineId, machineId);
    }

    public Optional<TriggerSchedule> findSchedule(UUID tenantId, UUID folderId, String name) {
        return database.query("""
                SELECT enabled, cron, interval_minutes, timezone, calendar_id
                  FROM triggers WHERE tenant_id = ? AND folder_id = ? AND name = ?
                """, (rs, rowNumber) -> new TriggerSchedule(rs.getBoolean(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getObject(5, UUID.class)),
                tenantId, folderId, name).stream().findFirst();
    }

    public boolean existsInFolder(UUID tenantId, UUID folderId, String name) {
        return database.exists("SELECT count(*) FROM triggers WHERE tenant_id = ? AND folder_id = ? AND name = ?",
                tenantId, folderId, name);
    }

    /** Folder-folder tempat pemicu bernama itu ada, folder bawaan lebih dulu. */
    public List<FolderLocation> findLocations(UUID tenantId, String name) {
        return database.query("""
                SELECT t.folder_id, f.is_default
                  FROM triggers t
                  JOIN folders f ON f.id = t.folder_id
                 WHERE t.tenant_id = ? AND t.name = ?
                 ORDER BY f.is_default DESC, t.created_at
                """, (rs, rowNumber) -> new FolderLocation(rs.getObject(1, UUID.class), rs.getBoolean(2)),
                tenantId, name);
    }

    /**
     * Pemicu tetap di foldernya: proses yang boleh dipilihnya hanya proses di
     * folder yang sama, karena pemicu selalu tinggal bersama proses yang
     * dijalankannya.
     */
    public void update(UUID tenantId, UUID folderId, TriggerDefinition trigger, OffsetDateTime nextRunAt) {
        database.update("""
                UPDATE triggers
                   SET process_name = ?, robot_name = ?, type = ?, cron = ?,
                       interval_minutes = ?, enabled = ?, next_run_at = ?,
                       priority = ?, timezone = ?, runtime_type = ?, calendar_id = ?
                 WHERE tenant_id = ? AND folder_id = ? AND name = ?
                """, trigger.processName(), trigger.robotName(), trigger.type(), trigger.cron(),
                trigger.intervalMinutes(), trigger.enabled(), nextRunAt,
                trigger.priority(), trigger.timezone(), trigger.runtimeType(), trigger.calendarId(),
                tenantId, folderId, trigger.name());
    }

    public void insert(UUID tenantId, UUID folderId, TriggerDefinition trigger, OffsetDateTime nextRunAt) {
        database.update("""
                INSERT INTO triggers
                    (id, tenant_id, folder_id, name, process_name, robot_name, type, cron,
                     interval_minutes, enabled, next_run_at, created_at,
                     priority, timezone, runtime_type, calendar_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, folderId, trigger.name(), trigger.processName(),
                trigger.robotName(), trigger.type(), trigger.cron(), trigger.intervalMinutes(), trigger.enabled(),
                nextRunAt, trigger.priority(), trigger.timezone(), trigger.runtimeType(), trigger.calendarId());
    }

    public void setEnabled(UUID tenantId, UUID folderId, String name, boolean enabled, OffsetDateTime nextRunAt) {
        database.update("""
                UPDATE triggers SET enabled = ?, next_run_at = ?
                 WHERE tenant_id = ? AND folder_id = ? AND name = ?
                """, enabled, nextRunAt, tenantId, folderId, name);
    }

    public int delete(UUID tenantId, UUID folderId, String name) {
        return database.update("DELETE FROM triggers WHERE tenant_id = ? AND folder_id = ? AND name = ?",
                tenantId, folderId, name);
    }

    // -----------------------------------------------------------------
    // Penjadwal
    // -----------------------------------------------------------------

    /** Id pemicu yang sudah waktunya jalan, paling lama tertunda lebih dulu. Belum dikunci. */
    public List<UUID> findDueTriggerIds() {
        return database.query("""
                SELECT id FROM triggers
                 WHERE enabled = TRUE
                   AND next_run_at IS NOT NULL
                   AND next_run_at <= now()
                 ORDER BY next_run_at
                """, (rs, rowNumber) -> rs.getObject(1, UUID.class));
    }

    /**
     * Kunci satu pemicu yang MASIH jatuh tempo, di dalam transaksi pemanggilnya.
     *
     * <p>SKIP LOCKED penting kalau nanti ada lebih dari satu OpenOrchestrator: dua
     * penjadwal yang membaca daftar yang sama akan menjadwalkan tiap pemicu dua
     * kali, dan yang terlihat adalah automasi berjalan ganda tanpa sebab.
     * Syarat jatuh temponya diperiksa ULANG di sini: penjadwal lain mungkin
     * sudah menjalankannya sesudah daftarnya dibaca.
     *
     * @return kosong kalau sudah dipegang penjadwal lain atau tidak lagi jatuh tempo
     */
    public Optional<DueTrigger> lockIfDue(UUID triggerId) {
        return database.query("""
                SELECT id, tenant_id, folder_id, name, process_name, robot_name,
                       interval_minutes, cron, priority, timezone, calendar_id, next_run_at
                  FROM triggers
                 WHERE id = ?
                   AND enabled = TRUE
                   AND next_run_at IS NOT NULL
                   AND next_run_at <= now()
                 FOR UPDATE SKIP LOCKED
                """, (rs, rowNumber) -> new DueTrigger(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("folder_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("process_name"),
                        rs.getString("robot_name"),
                        rs.getInt("interval_minutes"),
                        rs.getString("cron"),
                        rs.getString("priority"),
                        rs.getString("timezone"),
                        rs.getObject("calendar_id", UUID.class),
                        rs.getObject("next_run_at", OffsetDateTime.class)),
                triggerId).stream().findFirst();
    }

    /**
     * Pemicu yang dilewati karena hari non-kerja: hanya waktu berikutnya yang
     * maju; {@code last_run_at} tetap, karena memang tidak ada yang dijalankan.
     */
    public void recordSkip(UUID triggerId, OffsetDateTime nextRunAt) {
        database.update("UPDATE triggers SET next_run_at = ? WHERE id = ?", nextRunAt, triggerId);
    }

    /** Pemicu aktif yang memakai kalender ini. */
    public List<CalendarTrigger> findEnabledByCalendar(UUID tenantId, UUID calendarId) {
        return database.query("""
                SELECT id, cron, interval_minutes, timezone, next_run_at
                  FROM triggers
                 WHERE tenant_id = ? AND calendar_id = ? AND enabled
                """, (rs, rowNumber) -> new CalendarTrigger(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getInt(3), rs.getString(4), rs.getObject(5, OffsetDateTime.class)),
                tenantId, calendarId);
    }

    public void updateNextRun(UUID triggerId, OffsetDateTime nextRunAt) {
        database.update("UPDATE triggers SET next_run_at = ? WHERE id = ?", nextRunAt, triggerId);
    }

    public void recordRun(UUID triggerId, OffsetDateTime nextRunAt) {
        database.update("UPDATE triggers SET last_run_at = now(), next_run_at = ? WHERE id = ?", nextRunAt, triggerId);
    }

    public void disable(UUID triggerId) {
        database.update("UPDATE triggers SET enabled = FALSE, next_run_at = NULL WHERE id = ?", triggerId);
    }
}
