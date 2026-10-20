package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Timestamps;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.dto.response.SessionTimeoutSettingsResponse;
import id.jakforge.openorchestrator.repository.SessionSettingsRepository;
import id.jakforge.openorchestrator.repository.SessionSettingsRepository.SettingsRow;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kebijakan batas waktu menganggur sesi dasbor (V18), per penyewa.
 *
 * <p>Server sumber kebenarannya: dasbor membaca nilainya dari sini, dan
 * {@link DashboardSessionService} menegakkannya untuk setiap permintaan.
 * Nilai dari klien tidak pernah dipercaya — setiap perubahan diperiksa di sini.
 */
@Service
@RequiredArgsConstructor
public class SessionSettingsService {

    /** Penyewa yang belum pernah menyimpan kebijakannya — sama dengan DEFAULT di V18. */
    static final SessionPolicy DEFAULT_POLICY = new SessionPolicy(true, 30, 2);
    static final List<Integer> DURATION_OPTIONS = List.of(5, 10, 15, 30, 60, 120);
    static final List<Integer> WARNING_OPTIONS = List.of(0, 1, 2, 5);
    static final int MAX_WARNING_MINUTES = 10;
    /** Perubahan kebijakan berlaku untuk semua sesi paling lambat sesudah selang ini. */
    static final Duration POLICY_CACHE = Duration.ofSeconds(10);

    static final String REQUIRED_CODE = "SESSION_TIMEOUT_REQUIRED";
    static final String INVALID_CODE = "SESSION_TIMEOUT_INVALID";

    private final SessionSettingsRepository repository;
    private final OpenOrchestratorProperties properties;
    private final Clock clock;

    private final Map<UUID, CachedPolicy> cache = new ConcurrentHashMap<>();

    /** Kebijakan yang berlaku. {@code warningBeforeLogoutMinutes} 0 = tanpa peringatan. */
    public record SessionPolicy(boolean enabled, int idleTimeoutMinutes, int warningBeforeLogoutMinutes) {

        public Duration idleTimeout() {
            return Duration.ofMinutes(idleTimeoutMinutes);
        }
    }

    private record CachedPolicy(SessionPolicy policy, Instant until) {
    }

    /**
     * Kebijakan yang DITEGAKKAN untuk penyewa ini. Batas server menang atas
     * yang tersimpan: kalau rentangnya dipersempit atau batas waktunya
     * diwajibkan sesudah kebijakan disimpan, yang ditegakkan tetap di dalam
     * aturan server.
     */
    public SessionPolicy policy(UUID tenantId) {
        Instant now = clock.instant();
        CachedPolicy cached = cache.get(tenantId);
        if (cached != null && cached.until().isAfter(now)) return cached.policy();

        SessionPolicy stored = repository.find(tenantId)
                .map(r -> new SessionPolicy(r.idleTimeoutEnabled(), r.idleTimeoutMinutes(), r.warningBeforeLogoutMinutes()))
                .orElse(DEFAULT_POLICY);

        int minutes = Math.max(minMinutes(), Math.min(maxMinutes(), stored.idleTimeoutMinutes()));
        int warning = Math.max(0, Math.min(stored.warningBeforeLogoutMinutes(), minutes - 1));
        SessionPolicy effective = new SessionPolicy(stored.enabled() || required(), minutes, warning);

        cache.put(tenantId, new CachedPolicy(effective, now.plus(POLICY_CACHE)));
        return effective;
    }

    public SessionTimeoutSettingsResponse get(OpenOrchestratorPrincipal principal) {
        SessionPolicy policy = policy(principal.tenantId());
        SettingsRow row = repository.find(principal.tenantId()).orElse(null);

        return new SessionTimeoutSettingsResponse(policy.enabled(), policy.idleTimeoutMinutes(),
                policy.warningBeforeLogoutMinutes(),
                row == null || row.updatedBy() == null ? null : Timestamps.format(row.updatedAt()),
                row == null ? null : row.updatedBy(), minMinutes(), maxMinutes(), required(),
                properties.jwt().expirationMinutes(),
                DURATION_OPTIONS.stream().filter(m -> m >= minMinutes() && m <= maxMinutes()).toList(),
                WARNING_OPTIONS);
    }

    /**
     * Simpan kebijakan penyewa (settings.update — diperiksa PermissionInterceptor).
     * Badan dibaca sebagai peta supaya 15.5 atau "15" ditolak, bukan dibulatkan
     * diam-diam oleh pengurai JSON.
     */
    public SessionTimeoutSettingsResponse update(OpenOrchestratorPrincipal principal, Map<String, Object> body) {
        Map<String, Object> values = body == null ? Map.of() : body;

        Object enabledValue = values.get("idleTimeoutEnabled");
        if (!(enabledValue instanceof Boolean enabled)) {
            throw invalid("idleTimeoutEnabled wajib diisi true atau false.");
        }

        int minutes = wholeNumber(values.get("idleTimeoutMinutes"), "Batas waktu menganggur");
        int warning = wholeNumber(values.get("warningBeforeLogoutMinutes"), "Peringatan sebelum keluar");

        if (!enabled && required()) {
            throw ApiException.badRequest("Kebijakan keamanan server mewajibkan batas waktu menganggur; "
                    + "batas waktunya tidak bisa dimatikan.").withCode(REQUIRED_CODE);
        }
        if (minutes < minMinutes() || minutes > maxMinutes()) {
            throw invalid("Batas waktu menganggur harus " + minMinutes() + "–" + maxMinutes() + " menit.");
        }
        if (warning < 0 || warning > MAX_WARNING_MINUTES) {
            throw invalid("Peringatan sebelum keluar harus 0–" + MAX_WARNING_MINUTES + " menit (0 = tanpa peringatan).");
        }
        if (warning >= minutes) {
            throw invalid("Peringatan sebelum keluar harus lebih singkat daripada batas waktu menganggur ("
                    + minutes + " menit).");
        }

        repository.save(principal.tenantId(), enabled, minutes, warning, principal.username());

        // Berlaku untuk SEMUA sesi penyewa ini pada permintaan berikutnya, bukan
        // hanya di peramban admin.
        cache.remove(principal.tenantId());

        return get(principal);
    }

    int minMinutes() {
        return properties.session().idleTimeoutMinMinutes();
    }

    /** Tidak lebih lama dari umur token: sesudah itu tokennya habis juga. */
    int maxMinutes() {
        return (int) Math.max(minMinutes(),
                Math.min(properties.session().idleTimeoutMaxMinutes(), properties.jwt().expirationMinutes()));
    }

    boolean required() {
        return properties.session().idleTimeoutRequired();
    }

    private static int wholeNumber(Object value, String label) {
        if (value instanceof Integer i) return i;
        if (value instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return l.intValue();
        throw invalid(label + " harus bilangan bulat menit.");
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest(message).withCode(INVALID_CODE);
    }
}
