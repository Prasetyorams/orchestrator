package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.common.LikePatterns;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Jejak audit: siapa mengubah apa, dan kapan. */
@Repository
@RequiredArgsConstructor
public class AuditRepository {

    /** Panjang kolom audit_logs di V4. */
    private static final int USERNAME_MAX_LENGTH = 80;
    private static final int COMPONENT_MAX_LENGTH = 60;
    private static final int ACTION_MAX_LENGTH = 60;
    private static final int TARGET_MAX_LENGTH = 400;
    private static final int DETAIL_MAX_LENGTH = 600;

    private static final String ELLIPSIS = "…";

    private final Database database;

    /**
     * Catat satu perubahan.
     *
     * <p>Teks dipotong ke panjang kolomnya di sini, bukan dibiarkan ditolak
     * basis data: jejak yang gagal ditulis karena nama berkasnya terlalu
     * panjang adalah jejak yang hilang tanpa suara.
     */
    public void insert(UUID tenantId, String username, String component, String action,
                       String target, String detail) {
        database.update("""
                INSERT INTO audit_logs (tenant_id, username, component, action, target, detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, tenantId, truncate(username, USERNAME_MAX_LENGTH), truncate(component, COMPONENT_MAX_LENGTH),
                truncate(action, ACTION_MAX_LENGTH), truncate(target, TARGET_MAX_LENGTH),
                truncate(detail, DETAIL_MAX_LENGTH));
    }

    /**
     * Daftar terbaru lebih dulu.
     *
     * @param component hanya komponen ini (Proses, Aset, ...); null berarti semua
     * @param keyword   potongan nama pengguna atau sasaran, tanpa membedakan huruf besar
     */
    public List<Map<String, Object>> findRecent(UUID tenantId, String component, String keyword, int limit) {
        List<String> conditions = new ArrayList<>(List.of("tenant_id = ?"));
        List<Object> args = new ArrayList<>(List.of(tenantId));

        if (component != null && !component.isBlank()) {
            conditions.add("component = ?");
            args.add(component);
        }

        if (keyword != null && !keyword.isBlank()) {
            conditions.add("(username ILIKE ? OR target ILIKE ?)");
            String pattern = LikePatterns.containing(keyword);
            args.add(pattern);
            args.add(pattern);
        }

        args.add(limit);

        return database.queryRows("""
                SELECT id, username, component, action, target, detail, created_at
                  FROM audit_logs
                 WHERE %s
                 ORDER BY id DESC
                 LIMIT ?
                """.formatted(String.join(" AND ", conditions)), args.toArray());
    }

    /** Komponen yang pernah tercatat, untuk pilihan penyaring. */
    public List<Map<String, Object>> countByComponent(UUID tenantId) {
        return database.queryRows("""
                SELECT component, count(*) AS total
                  FROM audit_logs WHERE tenant_id = ?
                 GROUP BY component
                 ORDER BY component
                """, tenantId);
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 1) + ELLIPSIS;
    }
}
