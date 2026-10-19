package id.jakforge.openorchestrator.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Akses data kalender hari non-kerja (V16) dan tanggal-tanggalnya. */
@Repository
@RequiredArgsConstructor
public class CalendarRepository {

    /** Satu tanggal non-kerja beserta catatannya. */
    public record CalendarDay(LocalDate day, String note) {
    }

    /**
     * Ringkasan per kalender. Hitungan "akan datang" memakai tanggal basis
     * data; hanya untuk ditampilkan, tidak dipakai penjadwal.
     */
    private static final String SUMMARY = """
            SELECT c.id, c.name, c.description, c.created_at, c.updated_at,
                   (SELECT count(*) FROM calendar_dates d WHERE d.calendar_id = c.id) AS day_count,
                   (SELECT count(*) FROM calendar_dates d
                     WHERE d.calendar_id = c.id AND d.day >= CURRENT_DATE) AS upcoming_day_count,
                   (SELECT min(d.day) FROM calendar_dates d
                     WHERE d.calendar_id = c.id AND d.day >= CURRENT_DATE) AS next_day,
                   (SELECT count(*) FROM triggers t WHERE t.calendar_id = c.id) AS trigger_count
              FROM calendars c
            """;

    private final Database database;

    public List<Map<String, Object>> findAll(UUID tenantId) {
        return database.queryRows(SUMMARY + " WHERE c.tenant_id = ? ORDER BY lower(c.name)", tenantId);
    }

    public Optional<Map<String, Object>> find(UUID tenantId, UUID id) {
        return database.queryRows(SUMMARY + " WHERE c.tenant_id = ? AND c.id = ?", tenantId, id).stream().findFirst();
    }

    public boolean exists(UUID tenantId, UUID id) {
        return database.exists("SELECT count(*) FROM calendars WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    /** Nama sudah dipakai kalender lain (tanpa membedakan huruf besar). */
    public boolean nameTaken(UUID tenantId, String name, UUID exceptId) {
        return database.exists("""
                SELECT count(*) FROM calendars
                 WHERE tenant_id = ? AND lower(name) = lower(?) AND id <> ?
                """, tenantId, name, exceptId == null ? new UUID(0, 0) : exceptId);
    }

    public List<CalendarDay> findDays(UUID calendarId) {
        return database.query("SELECT day, note FROM calendar_dates WHERE calendar_id = ? ORDER BY day",
                (rs, rowNumber) -> new CalendarDay(rs.getObject(1, LocalDate.class), rs.getString(2)), calendarId);
    }

    /** Tanggal-tanggalnya saja, untuk penjadwal. Kosong untuk kalender null. */
    public Set<LocalDate> findDates(UUID calendarId) {
        if (calendarId == null) return Set.of();

        return new HashSet<>(database.query("SELECT day FROM calendar_dates WHERE calendar_id = ?",
                (rs, rowNumber) -> rs.getObject(1, LocalDate.class), calendarId));
    }

    /** Tanggal dalam rentang [from, until], urut; untuk dikirim ke agent. */
    public List<LocalDate> findDatesBetween(UUID calendarId, LocalDate from, LocalDate until) {
        return database.query("""
                SELECT day FROM calendar_dates
                 WHERE calendar_id = ? AND day BETWEEN ? AND ?
                 ORDER BY day
                """, (rs, rowNumber) -> rs.getObject(1, LocalDate.class), calendarId, from, until);
    }

    public Optional<String> findName(UUID tenantId, UUID id) {
        return database.queryScalar("SELECT name FROM calendars WHERE tenant_id = ? AND id = ?", tenantId, id)
                .map(String::valueOf);
    }

    public void insert(UUID id, UUID tenantId, String name, String description) {
        database.update("""
                INSERT INTO calendars (id, tenant_id, name, description, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, id, tenantId, name, description);
    }

    public int update(UUID tenantId, UUID id, String name, String description) {
        return database.update("""
                UPDATE calendars SET name = ?, description = ?, updated_at = now()
                 WHERE tenant_id = ? AND id = ?
                """, name, description, tenantId, id);
    }

    /** Ganti seluruh tanggal kalender itu dengan daftar ini. */
    public void replaceDays(UUID calendarId, List<CalendarDay> days) {
        database.update("DELETE FROM calendar_dates WHERE calendar_id = ?", calendarId);
        if (days.isEmpty()) return;

        database.jdbcTemplate().batchUpdate(
                "INSERT INTO calendar_dates (calendar_id, day, note) VALUES (?, ?, ?)",
                days.stream().map(d -> new Object[]{calendarId, Date.valueOf(d.day()), d.note()}).toList());
    }

    /** Pemicu yang memakai kalender ini, sebagai "folder / nama", urut. */
    public List<String> findTriggerNames(UUID tenantId, UUID calendarId) {
        return database.query("""
                SELECT f.name || ' / ' || t.name
                  FROM triggers t JOIN folders f ON f.id = t.folder_id
                 WHERE t.tenant_id = ? AND t.calendar_id = ?
                 ORDER BY lower(f.name), lower(t.name)
                """, (rs, rowNumber) -> rs.getString(1), tenantId, calendarId);
    }

    public int delete(UUID tenantId, UUID id) {
        return database.update("DELETE FROM calendars WHERE tenant_id = ? AND id = ?", tenantId, id);
    }
}
