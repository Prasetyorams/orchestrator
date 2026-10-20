package id.jakforge.openorchestrator.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Kebijakan sesi dasbor per penyewa (V18), satu baris per penyewa. */
@Repository
@RequiredArgsConstructor
public class SessionSettingsRepository {

    private final Database database;

    /** @param updatedAt null untuk baris yang belum pernah disimpan (nilai bawaan) */
    public record SettingsRow(boolean idleTimeoutEnabled, int idleTimeoutMinutes, int warningBeforeLogoutMinutes,
                              Instant updatedAt, String updatedBy) {
    }

    public Optional<SettingsRow> find(UUID tenantId) {
        List<SettingsRow> rows = database.query("""
                SELECT idle_timeout_enabled, idle_timeout_minutes, warning_before_logout_minutes, updated_at, updated_by
                  FROM session_settings WHERE tenant_id = ?
                """, SessionSettingsRepository::row, tenantId);
        return rows.stream().findFirst();
    }

    public void save(UUID tenantId, boolean enabled, int idleTimeoutMinutes, int warningBeforeLogoutMinutes,
                     String updatedBy) {
        database.update("""
                INSERT INTO session_settings
                    (tenant_id, idle_timeout_enabled, idle_timeout_minutes, warning_before_logout_minutes,
                     updated_at, updated_by)
                VALUES (?, ?, ?, ?, now(), ?)
                ON CONFLICT (tenant_id) DO UPDATE
                   SET idle_timeout_enabled = EXCLUDED.idle_timeout_enabled,
                       idle_timeout_minutes = EXCLUDED.idle_timeout_minutes,
                       warning_before_logout_minutes = EXCLUDED.warning_before_logout_minutes,
                       updated_at = EXCLUDED.updated_at,
                       updated_by = EXCLUDED.updated_by
                """, tenantId, enabled, idleTimeoutMinutes, warningBeforeLogoutMinutes, updatedBy);
    }

    private static SettingsRow row(ResultSet rs, int rowNumber) throws SQLException {
        OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
        return new SettingsRow(rs.getBoolean("idle_timeout_enabled"), rs.getInt("idle_timeout_minutes"),
                rs.getInt("warning_before_logout_minutes"), updatedAt == null ? null : updatedAt.toInstant(),
                rs.getString("updated_by"));
    }
}
