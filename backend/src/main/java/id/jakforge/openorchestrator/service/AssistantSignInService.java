package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Strings;
import id.jakforge.openorchestrator.common.Timestamps;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.dto.request.AssistantCodeRequest;
import id.jakforge.openorchestrator.dto.request.AssistantTokenRequest;
import id.jakforge.openorchestrator.dto.response.AssistantCodeResponse;
import id.jakforge.openorchestrator.dto.response.AssistantTokenResponse;
import id.jakforge.openorchestrator.dto.response.OkResponse;
import id.jakforge.openorchestrator.repository.AssistantSessionRepository;
import id.jakforge.openorchestrator.repository.UserRepository;
import id.jakforge.openorchestrator.security.AssistantSignIn;
import id.jakforge.openorchestrator.security.JwtService;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open Assistant masuk lewat dasbor, seperti UiPath Assistant (usulan tim
 * robot, PR orchestrator#4; ROBOT-API.md bagian 5).
 *
 * <ol>
 *   <li>Open Assistant membuka {@code /assistant/connect} di peramban dengan
 *       {@code state} dan {@code code_challenge} (PKCE S256).</li>
 *   <li>Orang yang sudah masuk ke dasbor menekan "Buka Open Assistant":
 *       {@link #createCode} memberi kode sekali pakai, berlaku 60 detik.</li>
 *   <li>Open Assistant menukarnya bersama {@code code_verifier}
 *       ({@link #exchange}) dan mendapat token akses satu jam, refresh token
 *       30 hari, dan robot attended miliknya di komputer itu.</li>
 *   <li>{@link #refresh} merotasi refresh token; {@link #logout} dan tombol
 *       "Cabut" di dasbor mengakhiri sambungannya.</li>
 * </ol>
 *
 * <p>Token akses membawa id sambungannya. Sambungan yang dicabut memutus
 * tokennya saat itu juga ({@link #requireActive}), tidak menunggu satu jam.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantSignInService {

    static final Duration CODE_TTL = Duration.ofSeconds(60);
    static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(1);
    /** Bergeser: setiap pembaruan memperpanjangnya lagi. */
    static final Duration REFRESH_TTL = Duration.ofDays(30);

    /**
     * Status sambungan diingat sebentar: denyut tiap lima detik tidak perlu
     * menjadi kueri tiap lima detik. Pencabutan dari OpenOrchestrator sendiri
     * langsung melupakannya, jadi yang menunggu hanya sambungan yang berakhir
     * karena kedaluwarsa.
     */
    static final Duration ACTIVE_CACHE = Duration.ofSeconds(30);

    static final int MAX_MACHINE_NAME = 160;
    static final int MAX_CLIENT_VERSION = 40;

    /** errorCode untuk kode atau refresh token yang tidak bisa dipakai (OAuth 2.0, RFC 6749 5.2). */
    static final String INVALID_GRANT = "invalid_grant";
    static final String INVALID_REQUEST = "invalid_request";

    private static final String SESSION_ENDED =
            "Sambungan Open Assistant ini sudah berakhir atau dicabut. Sambungkan lagi dari Open Assistant.";

    private record CachedActive(boolean active, Instant expiresAt) {
    }

    private final AssistantSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final RobotService robotService;
    private final JwtService jwtService;
    private final Clock clock;

    private final Map<UUID, CachedActive> activeSessions = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------
    // Dasbor: menyetujui sambungan
    // -----------------------------------------------------------------

    /**
     * Kode sekali pakai untuk Open Assistant yang meminta disambungkan, atas
     * nama orang yang sedang masuk ke dasbor.
     *
     * <p>Hanya dari dasbor: token Open Assistant sendiri tidak boleh
     * menyetujui sambungan baru — kalau bisa, satu PC yang dibobol bisa
     * menyambungkan PC lain atas nama pemiliknya.
     */
    @Transactional
    public AssistantCodeResponse createCode(OpenOrchestratorPrincipal principal, AssistantCodeRequest request) {
        if (!principal.isUser() || principal.isAssistant()) {
            throw ApiException.forbidden("Open Assistant hanya bisa disambungkan dari dasbor.");
        }

        if (!AssistantSignIn.CLIENT.equals(request.client())) {
            throw invalidRequest("Aplikasi '" + request.client() + "' tidak dikenal. Yang bisa disambungkan hanya Open Assistant.");
        }

        if (!AssistantSignIn.REDIRECT_URI.equals(request.redirectUri())) {
            throw invalidRequest("Alamat kembali tidak dikenal. Mulai lagi dari tombol Masuk lewat dasbor di Open Assistant.");
        }

        if (!AssistantSignIn.CHALLENGE_METHOD.equals(request.codeChallengeMethod())
                || !AssistantSignIn.isChallenge(request.codeChallenge())) {
            throw invalidRequest("Permintaan sambungan tidak lengkap (code_challenge S256). Mulai lagi dari Open Assistant.");
        }

        if (!AssistantSignIn.isState(request.state())) {
            throw invalidRequest("Permintaan sambungan tidak lengkap (state). Mulai lagi dari Open Assistant.");
        }

        String code = AssistantSignIn.newSecret();
        OffsetDateTime expiresAt = later(CODE_TTL);

        sessionRepository.deleteStaleCodes();
        sessionRepository.insertCode(UUID.randomUUID(), principal.tenantId(), principal.userId(),
                AssistantSignIn.hash(code), AssistantSignIn.CLIENT, AssistantSignIn.REDIRECT_URI,
                request.codeChallenge(), limit(Strings.trimToNull(request.machine()), MAX_MACHINE_NAME), expiresAt);

        return new AssistantCodeResponse(AssistantSignIn.redirect(code, request.state()), Timestamps.format(expiresAt));
    }

    // -----------------------------------------------------------------
    // Open Assistant: token
    // -----------------------------------------------------------------

    /**
     * Tukar kode dengan token.
     *
     * <p>Setiap percobaan MENGHABISKAN kodenya, juga yang gagal karena
     * code_verifier salah: kode yang dicoba dengan verifier keliru berarti ada
     * yang menyadapnya. Kode yang dipakai kedua kali mencabut sambungan yang
     * sudah terbit darinya. Karena itu galat di sini tidak membatalkan
     * transaksinya — tanda "sudah dipakai" harus tetap tersimpan.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AssistantTokenResponse exchange(AssistantTokenRequest request) {
        if (request.code() == null || request.codeVerifier() == null) {
            throw invalidRequest("code dan codeVerifier wajib diisi.");
        }

        ApiException rejected = invalidGrant(
                "Kode masuk tidak sah, sudah dipakai, atau kedaluwarsa. Ulangi Masuk lewat dasbor dari Open Assistant.");

        Map<String, Object> code = sessionRepository.lockCode(AssistantSignIn.hash(request.code()))
                .orElseThrow(() -> rejected);
        UUID codeId = Uuids.parseOrNull((String) code.get("id"));

        if (Boolean.TRUE.equals(code.get("used"))) {
            int revoked = sessionRepository.revokeFromCode(codeId, "code_reused");
            if (revoked > 0) {
                activeSessions.clear();
                log.warn("Kode masuk Open Assistant dipakai dua kali; {} sambungan dari kode itu dicabut.", revoked);
            }
            throw rejected;
        }

        sessionRepository.markCodeUsed(codeId);

        if (!Boolean.TRUE.equals(code.get("live"))
                || !AssistantSignIn.verifierMatches(request.codeVerifier(), (String) code.get("codeChallenge"))) {
            throw rejected;
        }

        UUID tenantId = Uuids.parseOrNull((String) code.get("tenantId"));
        UUID userId = Uuids.parseOrNull((String) code.get("userId"));
        Map<String, Object> user = userRepository.findProfile(userId, tenantId)
                .filter(profile -> Boolean.TRUE.equals(profile.get("isActive")))
                .orElseThrow(() -> rejected);

        // Nama dari Open Assistant sendiri; kalau tidak dikirim, yang disebutnya di peramban.
        String machineName = limit(request.machineName() != null ? request.machineName()
                : Strings.trimToNull((String) code.get("machineName")), MAX_MACHINE_NAME);

        if (machineName == null) throw invalidRequest("machineName wajib diisi: nama komputer tempat Open Assistant berjalan.");

        String robotName = robotService.ensureAssistantRobot(tenantId, (String) user.get("username"), machineName);
        String refreshToken = AssistantSignIn.newSecret();
        UUID sessionId = UUID.randomUUID();

        sessionRepository.deleteStaleSessions();
        sessionRepository.insertSession(sessionId, tenantId, userId, codeId, robotName, machineName,
                limit(request.clientVersion(), MAX_CLIENT_VERSION), AssistantSignIn.hash(refreshToken),
                later(REFRESH_TTL));

        log.info("Open Assistant tersambung: {} di {} sebagai robot {}.", user.get("username"), machineName, robotName);

        return tokenResponse(sessionId, tenantId, user, robotName, refreshToken);
    }

    /**
     * Token baru dan refresh token baru; yang lama langsung tidak berlaku.
     *
     * <p>Refresh token LAMA yang dipakai lagi berarti tokennya disalin orang
     * lain: seluruh sambungan dicabut, termasuk yang sedang dipakai pencurinya.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AssistantTokenResponse refresh(String refreshToken) {
        if (refreshToken == null) throw invalidRequest("refreshToken wajib diisi.");

        String hash = AssistantSignIn.hash(refreshToken);
        Map<String, Object> session = sessionRepository.lockByRefresh(hash).orElse(null);

        if (session == null) {
            sessionRepository.findByPreviousRefresh(hash).ifPresent(id -> {
                if (sessionRepository.revoke(id, "refresh_reused") > 0) {
                    log.warn("Refresh token Open Assistant yang sudah diganti dipakai lagi; sambungan {} dicabut.", id);
                }
                activeSessions.remove(id);
            });

            throw sessionEnded();
        }

        UUID sessionId = Uuids.parseOrNull((String) session.get("id"));

        if (!Boolean.TRUE.equals(session.get("live"))) throw sessionEnded();

        UUID tenantId = Uuids.parseOrNull((String) session.get("tenantId"));
        UUID userId = Uuids.parseOrNull((String) session.get("userId"));
        Map<String, Object> user = userRepository.findProfile(userId, tenantId)
                .filter(profile -> Boolean.TRUE.equals(profile.get("isActive")))
                .orElse(null);

        if (user == null) {
            sessionRepository.revoke(sessionId, "user_inactive");
            activeSessions.remove(sessionId);
            throw sessionEnded();
        }

        String newRefreshToken = AssistantSignIn.newSecret();
        sessionRepository.rotate(sessionId, AssistantSignIn.hash(newRefreshToken), hash, later(REFRESH_TTL));

        return tokenResponse(sessionId, tenantId, user, (String) session.get("robotName"), newRefreshToken);
    }

    /** Tombol "Putuskan" di Open Assistant. Selalu 200: yang sudah berakhir memang sudah berakhir. */
    @Transactional
    public OkResponse logout(String refreshToken) {
        if (refreshToken != null) {
            String hash = AssistantSignIn.hash(refreshToken);

            sessionRepository.lockByRefresh(hash)
                    .map(session -> Uuids.parseOrNull((String) session.get("id")))
                    .or(() -> sessionRepository.findByPreviousRefresh(hash))
                    .ifPresent(id -> {
                        sessionRepository.revoke(id, "logout");
                        activeSessions.remove(id);
                    });
        }

        return OkResponse.success();
    }

    // -----------------------------------------------------------------
    // Dasbor: daftar dan cabut
    // -----------------------------------------------------------------

    /** Open Assistant yang tersambung atas nama orang ini — "Cabut" di menu profil. */
    public List<Map<String, Object>> sessions(OpenOrchestratorPrincipal principal) {
        requireUser(principal);

        List<Map<String, Object>> sessions = new ArrayList<>();

        for (Map<String, Object> row : sessionRepository.findActiveOfUser(principal.tenantId(), principal.userId())) {
            Map<String, Object> session = new LinkedHashMap<>(row);
            session.put("current", Objects.equals(Uuids.parseOrNull((String) row.get("id")),
                    principal.assistantSessionId()));
            sessions.add(session);
        }

        return sessions;
    }

    @Transactional
    public OkResponse revoke(OpenOrchestratorPrincipal principal, String id) {
        requireUser(principal);

        UUID sessionId = Uuids.parseOrNull(id);

        if (sessionId == null
                || sessionRepository.revokeOwn(principal.tenantId(), principal.userId(), sessionId, "revoked") == 0) {
            throw ApiException.notFound("Sambungan Open Assistant itu tidak ada atau sudah dicabut.");
        }

        activeSessions.remove(sessionId);
        return OkResponse.success();
    }

    /**
     * Semua sambungan seseorang berakhir: sandinya diganti, atau akunnya
     * dinonaktifkan atau dihapus. Token di PC-nya berhenti saat itu juga.
     */
    public void revokeAllOf(UUID tenantId, String username, String reason) {
        if (sessionRepository.revokeAllOfUser(tenantId, username, reason) > 0) activeSessions.clear();
    }

    // -----------------------------------------------------------------
    // PermissionInterceptor
    // -----------------------------------------------------------------

    /** 401 kalau sambungan yang menerbitkan token ini sudah dicabut atau berakhir. */
    public void requireActive(OpenOrchestratorPrincipal principal) {
        Instant now = clock.instant();
        UUID sessionId = principal.assistantSessionId();
        CachedActive cached = activeSessions.get(sessionId);

        boolean active;

        if (cached != null && cached.expiresAt().isAfter(now)) {
            active = cached.active();
        } else {
            active = sessionRepository.touchIfActive(principal.tenantId(), sessionId);
            activeSessions.put(sessionId, new CachedActive(active, now.plus(ACTIVE_CACHE)));

            // Yang sudah lewat dibuang sesekali supaya peta ini tidak tumbuh selamanya.
            if (activeSessions.size() > 10_000) activeSessions.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        }

        if (!active) throw ApiException.unauthorized(SESSION_ENDED).withCode("AssistantSessionRevoked");
    }

    // -----------------------------------------------------------------

    private AssistantTokenResponse tokenResponse(UUID sessionId, UUID tenantId, Map<String, Object> user,
                                                 String robotName, String refreshToken) {
        UUID userId = Uuids.parseOrNull((String) user.get("id"));
        String username = (String) user.get("username");

        JwtService.IssuedToken token = jwtService.issueAssistantToken(userId, tenantId, username,
                (String) user.get("role"), sessionId, ACCESS_TOKEN_TTL);

        return new AssistantTokenResponse(token.token(), Timestamps.format(token.expiresAt()), refreshToken,
                new AssistantTokenResponse.User(username, (String) user.get("displayName")), robotName);
    }

    private static void requireUser(OpenOrchestratorPrincipal principal) {
        if (!principal.isUser()) throw ApiException.forbidden("Hanya untuk pengguna.");
    }

    private OffsetDateTime later(Duration duration) {
        return OffsetDateTime.ofInstant(clock.instant().plus(duration), ZoneOffset.UTC);
    }

    private static String limit(String text, int max) {
        if (text == null) return null;
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static ApiException invalidRequest(String message) {
        return ApiException.badRequest(message).withCode(INVALID_REQUEST);
    }

    private static ApiException invalidGrant(String message) {
        return ApiException.badRequest(message).withCode(INVALID_GRANT);
    }

    private static ApiException sessionEnded() {
        return ApiException.unauthorized(SESSION_ENDED).withCode(INVALID_GRANT);
    }
}
