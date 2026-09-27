package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.model.Severity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Peringatan di lonceng bilah atas dan di halaman Peringatan. */
@Repository
@RequiredArgsConstructor
public class AlertRepository {

    private final Database database;

    public List<Map<String, Object>> findRecent(UUID tenantId, boolean unreadOnly, int limit) {
        return findRecent(tenantId, unreadOnly, List.of(), limit);
    }

    /** @param severities nilai severity yang boleh tampil; kosong berarti semua. */
    public List<Map<String, Object>> findRecent(UUID tenantId, boolean unreadOnly, Collection<String> severities,
                                                int limit) {
        List<Object> args = new ArrayList<>(List.of(tenantId, unreadOnly));
        String severityFilter = "";

        if (!severities.isEmpty()) {
            severityFilter = " AND severity IN (" + Database.placeholders(severities.size()) + ")";
            args.addAll(severities);
        }

        args.add(limit);

        return database.queryRows("""
                SELECT id, severity, title, message, source, is_read, created_at
                  FROM alerts
                 WHERE tenant_id = ? AND (NOT ? OR NOT is_read)%s
                 ORDER BY id DESC
                 LIMIT ?
                """.formatted(severityFilter), args.toArray());
    }

    public void insert(UUID tenantId, Severity severity, String title, String message, String source) {
        database.update("""
                INSERT INTO alerts (tenant_id, severity, title, message, source, is_read, created_at)
                VALUES (?, ?, ?, ?, ?, FALSE, now())
                """, tenantId, severity.storedValue(), title, message, source);
    }

    public int markRead(UUID tenantId, long alertId) {
        return database.update("UPDATE alerts SET is_read = TRUE WHERE tenant_id = ? AND id = ?", tenantId, alertId);
    }

    public int markAllRead(UUID tenantId) {
        return database.update("UPDATE alerts SET is_read = TRUE WHERE tenant_id = ? AND NOT is_read", tenantId);
    }

    public long countUnread(UUID tenantId) {
        return database.count("SELECT count(*) FROM alerts WHERE tenant_id = ? AND NOT is_read", tenantId);
    }
}
