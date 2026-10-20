package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Timestamps;
import id.jakforge.openorchestrator.dto.response.SessionStateResponse;
import id.jakforge.openorchestrator.repository.DashboardSessionRepository;
import id.jakforge.openorchestrator.repository.DashboardSessionRepository.SessionRow;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.SessionSettingsService.SessionPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sesi dasbor (V18): batas waktu menganggur yang DITEGAKKAN server.
 *
 * <p>Token pengguna adalah JWT tanpa status; tanpa ini, token yang dicuri atau
 * peramban yang ditinggal terbuka tetap berlaku sampai umurnya habis. Token
 * dasbor menyebut satu baris {@code dashboard_sessions}, dan setiap
 * permintaannya ({@link #requireActive}, dari PermissionInterceptor) ditolak
 * 401 kalau sesi itu dicabut, habis umurnya, atau menganggur melewati batas.
 *
 * <p><b>Aktivitas</b> hanya dicatat lewat {@link #recordActivity} — laporan
 * dasbor bahwa ORANG sedang memakai halaman (mouse, papan ketik, gulir,
 * sentuh, pindah halaman). Permintaan lain, termasuk penarikan data berkala,
 * hanya DIPERIKSA, tidak pernah memperpanjang sesi.
 *
 * <p>Lingkupnya per sesi: setiap masuk lewat dasbor satu sesi. Tab-tab di
 * peramban yang sama berbagi token, jadi berbagi sesi; peramban atau perangkat
 * lain punya sesinya sendiri dan tidak saling memperpanjang.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardSessionService {

    /** Klien yang membuat sesi dasbor di POST /api/auth/login. */
    public static final String DASHBOARD_CLIENT = "dashboard";

    /**
     * Kelonggaran untuk jeda antara aktivitas terakhir di peramban dan laporan
     * yang sampai ke server. Dasbor sendiri keluar tepat pada batasnya.
     */
    static final Duration GRACE = Duration.ofSeconds(15);
    /** Baris sesi dibaca ulang paling lambat sesudah selang ini; pencabutan dan aktivitas mengosongkannya. */
    static final Duration ROW_CACHE = Duration.ofSeconds(5);

    static final String IDLE_CODE = "SessionIdleTimeout";
    static final String ENDED_CODE = "SessionEnded";

    static final String REASON_LOGOUT = "logout";
    static final String REASON_IDLE = "idle_timeout";

    static final String AUDIT_COMPONENT = "Sesi";

    private final DashboardSessionRepository repository;
    private final SessionSettingsService settingsService;
    private final AuditService auditService;
    private final Clock clock;

    private final Map<UUID, CachedRow> cache = new ConcurrentHashMap<>();

    private record CachedRow(SessionRow row, Instant until) {
    }

    /** Sesi baru untuk satu masuk lewat dasbor; umurnya sama dengan tokennya. */
    public UUID start(UUID tenantId, UUID userId, String username, Instant expiresAt, String userAgent) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();

        repository.insert(id, tenantId, userId, username, now, expiresAt, truncate(userAgent, 300));

        // Sesekali, bukan per permintaan: baris lama hanya berguna untuk ditelusuri.
        try {
            repository.deleteStale(now);
        } catch (RuntimeException e) {
            log.warn("Sesi dasbor lama gagal dibersihkan.", e);
        }

        return id;
    }

    /**
     * 401 kalau sesi dasbor pemanggil sudah berakhir. Yang menganggur melewati
     * batas dicabut di sini juga — sekali, dengan jejak audit — supaya
     * penolakannya tetap sama walau kebijakannya diubah sesudahnya.
     */
    public void requireActive(OpenOrchestratorPrincipal principal) {
        Instant now = clock.instant();
        SessionRow row = cachedRow(principal, now);

        checkAlive(principal, row, now);
    }

    /**
     * Ada aktivitas orang di dasbor: catat, kalau sesinya masih berlaku saat
     * ini. Sesi yang sudah lewat batas TIDAK bisa diperpanjang lewat sini —
     * pemeriksaannya ada di PermissionInterceptor sebelum ini, dan sekali lagi
     * di UPDATE-nya sendiri.
     */
    public SessionStateResponse recordActivity(OpenOrchestratorPrincipal principal) {
        if (!principal.isDashboard()) return tokenState(principal);

        Instant now = clock.instant();
        SessionPolicy policy = settingsService.policy(principal.tenantId());
        Instant cutoff = policy.enabled() ? now.minus(policy.idleTimeout()).minus(GRACE) : null;

        boolean recorded = repository.recordActivity(principal.tenantId(), principal.dashboardSessionId(), now, cutoff);
        cache.remove(principal.dashboardSessionId());

        SessionRow row = repository.find(principal.tenantId(), principal.dashboardSessionId()).orElse(null);
        if (!recorded) {
            checkAlive(principal, row, now);
            // Masih hidup tetapi tidak tercatat: hanya mungkin kalau barisnya
            // berubah di antara dua kueri. Tetap ditolak — lebih aman.
            throw ended("Sesi sudah berakhir. Silakan masuk lagi.", ENDED_CODE);
        }

        return dashboardState(row, policy, now);
    }

    /** Keadaan sesi tanpa mencatat aktivitas — untuk tab yang baru dibuka atau kembali aktif. */
    public SessionStateResponse state(OpenOrchestratorPrincipal principal) {
        if (!principal.isDashboard()) return tokenState(principal);

        Instant now = clock.instant();
        SessionRow row = repository.find(principal.tenantId(), principal.dashboardSessionId()).orElse(null);
        checkAlive(principal, row, now);

        return dashboardState(row, settingsService.policy(principal.tenantId()), now);
    }

    /** Keluar: sesi dasbor dicabut, tokennya berhenti di semua tab dan salinannya. */
    public void logout(OpenOrchestratorPrincipal principal) {
        if (!principal.isDashboard()) return;

        repository.revoke(principal.tenantId(), principal.dashboardSessionId(), clock.instant(), REASON_LOGOUT);
        cache.remove(principal.dashboardSessionId());
    }

    /**
     * Semua sesi dasbor seseorang berakhir: sandinya diganti atau direset,
     * akunnya dinonaktifkan atau dihapus.
     *
     * @param exceptSessionId sesi yang dibiarkan — sesi orang yang mengganti sandinya sendiri; boleh null
     */
    public void revokeAllOf(UUID tenantId, String username, String reason, UUID exceptSessionId) {
        if (repository.revokeAllOfUser(tenantId, username, clock.instant(), reason, exceptSessionId) > 0) {
            cache.clear();
        }
    }

    // -----------------------------------------------------------------

    private SessionRow cachedRow(OpenOrchestratorPrincipal principal, Instant now) {
        UUID id = principal.dashboardSessionId();
        CachedRow cached = cache.get(id);
        if (cached != null && cached.until().isAfter(now)) return cached.row();

        SessionRow row = repository.find(principal.tenantId(), id).orElse(null);
        if (row != null) cache.put(id, new CachedRow(row, now.plus(ROW_CACHE)));

        // Yang sudah lewat dibuang sesekali supaya peta ini tidak tumbuh selamanya.
        if (cache.size() > 10_000) cache.entrySet().removeIf(e -> e.getValue().until().isBefore(now));

        return row;
    }

    private void checkAlive(OpenOrchestratorPrincipal principal, SessionRow row, Instant now) {
        if (row == null) throw ended("Sesi tidak dikenal. Silakan masuk lagi.", ENDED_CODE);

        if (row.revokedAt() != null) {
            if (REASON_IDLE.equals(row.revokeReason())) throw idleEnded(settingsService.policy(principal.tenantId()));
            throw ended("Sesi sudah berakhir. Silakan masuk lagi.", ENDED_CODE);
        }

        if (!row.expiresAt().isAfter(now)) throw ended("Sesi sudah habis umurnya. Silakan masuk lagi.", ENDED_CODE);

        SessionPolicy policy = settingsService.policy(principal.tenantId());
        if (policy.enabled() && !idleDeadline(row, policy).isAfter(now)) {
            if (repository.revoke(principal.tenantId(), row.id(), now, REASON_IDLE)) {
                auditService.record(principal, AUDIT_COMPONENT, "Berakhir karena tidak aktif", null,
                        "Menganggur lebih dari " + policy.idleTimeoutMinutes() + " menit");
            }
            cache.remove(row.id());
            throw idleEnded(policy);
        }
    }

    private static Instant idleDeadline(SessionRow row, SessionPolicy policy) {
        return row.lastActivityAt().plus(policy.idleTimeout()).plus(GRACE);
    }

    private SessionStateResponse dashboardState(SessionRow row, SessionPolicy policy, Instant now) {
        return new SessionStateResponse(DASHBOARD_CLIENT, policy.enabled(), policy.idleTimeoutMinutes(),
                policy.warningBeforeLogoutMinutes(), Timestamps.format(row.lastActivityAt()),
                policy.enabled() ? Timestamps.format(row.lastActivityAt().plus(policy.idleTimeout())) : null,
                Timestamps.format(row.expiresAt()), Timestamps.format(now));
    }

    /**
     * Token tanpa sesi dasbor (Studio, robot, alat lain, atau token dasbor dari
     * sebelum V18): server TIDAK menegakkan batas menganggurnya. Kebijakan
     * penyewa tetap disebut, supaya dasbor yang masih memegang token lama bisa
     * menerapkannya di peramban sampai tokennya habis.
     */
    private SessionStateResponse tokenState(OpenOrchestratorPrincipal principal) {
        SessionPolicy policy = settingsService.policy(principal.tenantId());
        return new SessionStateResponse("token", policy.enabled(), policy.idleTimeoutMinutes(),
                policy.warningBeforeLogoutMinutes(), null, null, null, Timestamps.format(clock.instant()));
    }

    private static ApiException idleEnded(SessionPolicy policy) {
        return ended("Sesi berakhir karena tidak ada aktivitas selama " + policy.idleTimeoutMinutes()
                + " menit. Silakan masuk lagi.", IDLE_CODE);
    }

    private static ApiException ended(String message, String code) {
        return ApiException.unauthorized(message).withCode(code);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
