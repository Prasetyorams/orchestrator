package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.dto.request.ChangePasswordRequest;
import id.jakforge.openorchestrator.dto.request.LoginRequest;
import id.jakforge.openorchestrator.dto.request.UpdateProfileRequest;
import id.jakforge.openorchestrator.dto.response.LoginResponse;
import id.jakforge.openorchestrator.dto.response.StatusResponse;
import id.jakforge.openorchestrator.repository.UserRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.JwtService;
import id.jakforge.openorchestrator.security.Passwords;
import id.jakforge.openorchestrator.security.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Masuk, siapa saya, ubah profil, dan ganti kata sandi. */
@Service
@RequiredArgsConstructor
public class AuthService {

    /** users.auth_provider akun Windows AD (V19). */
    static final String AUTH_PROVIDER_AD = "AD";

    /**
     * Bentuk surel yang cukup untuk menangkap salah ketik, bukan validasi RFC.
     *
     * <p>Aturan RFC 5322 yang lengkap menerima alamat yang tidak pernah dipakai
     * orang dan hampir tidak menolak apa pun yang berguna. Yang benar-benar
     * terjadi adalah "@" yang terlupa atau spasi yang ikut tersalin.
     */
    static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final String USER_NO_LONGER_EXISTS = "Pengguna sudah tidak ada.";
    private static final String PERMISSIONS_FIELD = "permissions";

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PermissionService permissionService;
    private final AssistantSignInService assistantSignInService;
    private final DashboardSessionService dashboardSessionService;

    @Transactional
    public LoginResponse login(LoginRequest request) {
        return login(request, null);
    }

    /**
     * @param userAgent peramban yang masuk, dicatat di sesi dasbor (V18); null untuk klien lain
     */
    @Transactional
    public LoginResponse login(LoginRequest request, String userAgent) {
        if (request.username() == null) {
            throw ApiException.badRequest("Nama pengguna wajib diisi.");
        }

        // Jawaban yang SAMA untuk pengguna tak dikenal, akun nonaktif, dan kata
        // sandi salah. Jawaban yang berbeda memberi tahu penebak nama mana yang
        // benar-benar ada, dan itu memisahkan satu tebakan menjadi dua yang
        // jauh lebih murah.
        ApiException rejected = ApiException.unauthorized("Nama pengguna atau kata sandi salah.");

        Map<String, Object> user = userRepository.findForLogin(request.username()).orElseThrow(() -> rejected);

        if (!Boolean.TRUE.equals(user.get("isActive"))) throw rejected;

        // Akun Windows AD (V19) tidak punya sandi lokal dan hanya masuk lewat
        // POST /api/auth/login/ad. Jawabannya sama dengan sandi salah.
        if (AUTH_PROVIDER_AD.equals(user.get("authProvider"))) throw rejected;

        String storedHash = (String) user.get("passwordHash");

        if (!Passwords.verify(request.password(), storedHash)) throw rejected;

        UUID userId = Uuids.parseOrNull((String) user.get("id"));
        UUID tenantId = Uuids.parseOrNull((String) user.get("tenantId"));
        String role = (String) user.get("role");

        userRepository.recordLogin(userId);

        // Kata sandi yang tersimpan dengan putaran lebih sedikit di-hash ulang
        // SEKARANG, saat kata sandi polosnya ada di tangan. Ini satu-satunya
        // saat itu mungkin; sesudah ini yang tersimpan hanya hash-nya.
        if (Passwords.needsRehash(storedHash)) {
            userRepository.updatePasswordHash(userId, Passwords.hash(request.password()));
        }

        return issue(user, request.client(), userAgent);
    }

    /**
     * Token untuk pengguna yang SUDAH lolos semua pemeriksaan masuk — lokal
     * maupun Windows AD (V19), lewat jalan yang sama: sesi dasbor dan batas
     * waktu menganggurnya (V18) berlaku untuk keduanya.
     *
     * @param user baris pengguna: id, username, displayName, role, tenantId, tenantName
     */
    LoginResponse issue(Map<String, Object> user, String client, String userAgent) {
        UUID userId = Uuids.parseOrNull((String) user.get("id"));
        UUID tenantId = Uuids.parseOrNull((String) user.get("tenantId"));
        String username = (String) user.get("username");
        String role = (String) user.get("role");

        // Dasbor (V18): tokennya terikat ke sesi dasbor, yang menegakkan batas
        // waktu menganggur. Klien lain mendapat token biasa, persis seperti dulu.
        String token;
        if (DashboardSessionService.DASHBOARD_CLIENT.equalsIgnoreCase(client)) {
            Instant expiresAt = Instant.now().plus(Duration.ofMinutes(jwtService.expirationMinutes()));
            UUID sessionId = dashboardSessionService.start(tenantId, userId, username, expiresAt, userAgent);
            token = jwtService.issueDashboardToken(userId, tenantId, username, role, sessionId).token();
        } else {
            token = jwtService.issueToken(userId, tenantId, username, role);
        }

        return new LoginResponse(
                token,
                jwtService.expirationMinutes(),
                userId.toString(),
                username,
                (String) user.get("displayName"),
                role,
                tenantId.toString(),
                (String) user.get("tenantName"));
    }

    /**
     * Siapa saya, beserta pola izin peran saya — dasbor memakainya untuk tidak
     * menawarkan menu dan tombol yang pasti ditolak. Yang menjaga tetap server.
     */
    public Map<String, Object> getCurrentUser(OpenOrchestratorPrincipal principal) {
        Map<String, Object> profile = userRepository.findProfile(principal.userId(), principal.tenantId())
                // Token sah untuk pengguna yang sudah tidak ada. Itu bukan 500:
                // yang salah adalah tokennya, bukan servernya.
                .orElseThrow(() -> ApiException.unauthorized(USER_NO_LONGER_EXISTS));

        Map<String, Object> currentUser = new LinkedHashMap<>(profile);
        currentUser.put(PERMISSIONS_FIELD, new ArrayList<>(permissionService.patternsOf(principal)));

        return currentUser;
    }

    /**
     * Ubah nama tampilan dan surel milik pengguna yang sedang masuk.
     *
     * <p>Mengembalikan profil yang sudah tersimpan, bukan sekadar "OK", supaya
     * yang ditampilkan dasbor sesudahnya adalah nilai yang benar-benar ada di
     * basis data — termasuk pemangkasan spasi.
     */
    @Transactional
    public Map<String, Object> updateProfile(OpenOrchestratorPrincipal principal, UpdateProfileRequest request) {
        // Surel boleh kosong — itu berarti dihapus. Kalau diisi, bentuknya
        // diperiksa di sini, bukan hanya di formulir: API ini juga bisa
        // dipanggil langsung.
        if (request.email() != null && !EMAIL_PATTERN.matcher(request.email()).matches()) {
            throw ApiException.badRequest("Alamat surel tidak sah.");
        }

        if (userRepository.updateProfile(principal.userId(), principal.tenantId(), request.displayName(),
                request.email()) == 0) {
            throw ApiException.unauthorized(USER_NO_LONGER_EXISTS);
        }

        return getCurrentUser(principal);
    }

    @Transactional
    public StatusResponse changePassword(OpenOrchestratorPrincipal principal, ChangePasswordRequest request) {
        // Kata sandi lama tetap diminta walau penggunanya sudah membawa token
        // yang sah. Token bisa berasal dari layar yang ditinggal terbuka; kata
        // sandi lama hanya diketahui pemiliknya.
        //
        // Salah dijawab 400, BUKAN 401. Dasbor, Studio, dan JakRunner membuang
        // tokennya begitu menerima 401 — jadi 401 di sini membuat orang yang
        // salah ketik kata sandi lamanya langsung terlempar ke layar masuk.
        // Tokennya sah; yang salah isiannya. OpenOrchestrator .NET juga menjawab 400.
        boolean adAccount = userRepository.findAuthIdentity(principal.tenantId(), principal.username())
                .map(identity -> AUTH_PROVIDER_AD.equals(identity.get("authProvider"))).orElse(false);
        if (adAccount) {
            throw ApiException.badRequest("Kata sandi akun Windows AD diganti di Windows, bukan di Open Orchestrator.");
        }

        String storedHash = userRepository.findPasswordHash(principal.userId()).orElse(null);

        if (!Passwords.verify(request.currentPassword(), storedHash)) {
            throw ApiException.badRequest("Kata sandi saat ini salah.");
        }

        userRepository.updatePasswordHash(principal.userId(), Passwords.hash(request.newPassword()));

        // Sandi baru = semua Open Assistant yang tersambung atas nama orang ini
        // perlu disambungkan lagi, sama seperti di UiPath.
        assistantSignInService.revokeAllOf(principal.tenantId(), principal.username(), "password_changed");

        // Sesi dasbor lain atas nama orang ini (peramban atau perangkat lain)
        // ikut berakhir; sesi yang dipakai mengganti sandi dibiarkan.
        dashboardSessionService.revokeAllOf(principal.tenantId(), principal.username(), "password_changed",
                principal.dashboardSessionId());

        return StatusResponse.ok();
    }
}
