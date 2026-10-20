package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.dto.request.LoginRequest;
import id.jakforge.openorchestrator.dto.response.LoginResponse;
import id.jakforge.openorchestrator.repository.AuditRepository;
import id.jakforge.openorchestrator.repository.TenantRepository;
import id.jakforge.openorchestrator.repository.UserRepository;
import id.jakforge.openorchestrator.security.ad.ActiveDirectory;
import id.jakforge.openorchestrator.security.ad.AdAccount;
import id.jakforge.openorchestrator.security.ad.AdException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Masuk dengan akun Windows Active Directory (V19, POST /api/auth/login/ad).
 *
 * <p>Urutannya tetap, dan SEMUA langkah di server:
 * <ol>
 *   <li>kredensial diperiksa AD lewat LDAPS ({@link ActiveDirectory});</li>
 *   <li>identitas direktori (objectGUID) dibaca;</li>
 *   <li>pengguna yang DIDAFTARKAN admin dengan identitas itu dicari;</li>
 *   <li>status aktifnya diperiksa;</li>
 *   <li>hak masuknya (grup AD yang diizinkan, kalau disetel) diperiksa;</li>
 *   <li>baru token/sesi dibuat — lewat jalan yang sama dengan masuk lokal.</li>
 * </ol>
 *
 * <p>Tidak pernah membuat pengguna, memberi peran bawaan, atau menyimpan sandi.
 * Sandi AD yang benar untuk akun yang belum didaftarkan tetap ditolak.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdLoginService {

    static final String AUDIT_COMPONENT = "Sesi";
    static final String LOGIN_ACTION = "Masuk (Windows AD)";
    static final String FAILED_ACTION = "Masuk Windows AD gagal";
    static final String REJECTED_ACTION = "Masuk Windows AD ditolak";

    static final String INVALID_MESSAGE =
            "Nama pengguna atau kata sandi Windows AD salah, atau akun Windows Anda tidak bisa dipakai masuk saat ini.";

    private final ActiveDirectory directory;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final AuditRepository auditRepository;
    private final AuthService authService;

    public LoginResponse login(LoginRequest request, String userAgent) {
        if (!directory.enabled()) {
            throw ApiException.badRequest("Masuk dengan Windows AD tidak diaktifkan di server ini.").withCode("AD_DISABLED");
        }

        String attempted = ActiveDirectory.normalizeUsername(request.username());
        String shown = attempted != null ? attempted : "(nama tidak sah)";

        // 1–2. Kredensial dan identitas direktori.
        AdAccount account;
        try {
            account = directory.authenticate(request.username(), request.password());
        } catch (AdException e) {
            log.info("Masuk Windows AD gagal untuk '{}': {} — {}", shown, e.failure(), e.getMessage());
            audit(null, shown, FAILED_ACTION, shown, describe(e.failure()));

            if (e.isCredentialProblem()) {
                throw ApiException.unauthorized(INVALID_MESSAGE).withCode("AD_INVALID_CREDENTIALS");
            }
            throw ApiException.unavailable("Windows AD tidak dapat dihubungi saat ini. Coba lagi nanti, atau hubungi "
                    + "administrator.").withCode("AD_UNAVAILABLE");
        }

        // 3. Pengguna yang didaftarkan admin.
        Map<String, Object> user = registeredUser(account);

        UUID userId = Uuids.parseOrNull((String) user.get("id"));
        UUID tenantId = Uuids.parseOrNull((String) user.get("tenantId"));
        String username = (String) user.get("username");

        // 4. Aktif.
        if (!Boolean.TRUE.equals(user.get("isActive"))) {
            audit(tenantId, username, REJECTED_ACTION, username, "akun Open Orchestrator nonaktif");
            throw ApiException.forbidden("Akun Anda di Open Orchestrator nonaktif. Hubungi administrator.")
                    .withCode("AD_USER_INACTIVE");
        }

        // 5. Hak masuk: grup AD yang diizinkan.
        if (!account.allowedByGroups()) {
            audit(tenantId, username, REJECTED_ACTION, username, "bukan anggota grup Windows AD yang diizinkan");
            throw ApiException.forbidden("Akun Windows AD Anda tidak termasuk grup yang diizinkan masuk ke Open "
                    + "Orchestrator. Hubungi administrator.").withCode("AD_GROUP_NOT_ALLOWED");
        }

        // 6. Token — lewat jalan masuk lokal.
        userRepository.recordLogin(userId);
        audit(tenantId, username, LOGIN_ACTION, username, "AD " + account.samAccountName());
        return authService.issue(user, request.client(), userAgent);
    }

    /**
     * Identitas AD → pengguna internal, dengan objectGUID sebagai kuncinya.
     * Nama AD hanya dipakai untuk pengguna yang didaftarkan TANPA GUID (tanpa
     * akun layanan): tautannya dibuat sekali, saat masuk pertama, dan sesudah
     * itu GUID-nya yang harus cocok.
     */
    private Map<String, Object> registeredUser(AdAccount account) {
        Optional<Map<String, Object>> byGuid = userRepository.findAdUserByGuid(account.objectGuid());

        if (byGuid.isPresent()) {
            Map<String, Object> user = byGuid.get();
            // Nama AD diganti di direktori, orangnya sama: perbarui yang tersimpan.
            if (!account.samAccountName().equalsIgnoreCase(String.valueOf(user.get("adUsername")))) {
                UUID userId = Uuids.parseOrNull((String) user.get("id"));
                userRepository.updateAdUsername(userId, account.samAccountName());
                audit(Uuids.parseOrNull((String) user.get("tenantId")), (String) user.get("username"),
                        "Ubah nama Windows AD", (String) user.get("username"),
                        user.get("adUsername") + " → " + account.samAccountName() + " (GUID sama)");
            }
            return user;
        }

        Map<String, Object> byName = userRepository.findAdUserByAdUsername(account.samAccountName()).orElse(null);

        if (byName == null) {
            audit(null, account.samAccountName(), REJECTED_ACTION, account.samAccountName(),
                    "belum didaftarkan di Open Orchestrator");
            throw ApiException.forbidden("Akun Windows AD Anda belum didaftarkan di Open Orchestrator. "
                    + "Hubungi administrator.").withCode("AD_NOT_REGISTERED");
        }

        UUID tenantId = Uuids.parseOrNull((String) byName.get("tenantId"));
        String username = (String) byName.get("username");

        if (byName.get("adObjectGuid") != null) {
            // Nama sama, identitas lain: akunnya dihapus lalu dibuat ulang, atau
            // orang lain memakai nama itu. Bukan orang yang didaftarkan.
            audit(tenantId, username, REJECTED_ACTION, username, "identitas Windows AD tidak cocok dengan yang didaftarkan "
                    + "(GUID " + account.objectGuid() + ", terdaftar " + byName.get("adObjectGuid") + ")");
            throw ApiException.forbidden("Akun Windows AD ini tidak cocok dengan akun yang didaftarkan. Hubungi "
                    + "administrator.").withCode("AD_IDENTITY_MISMATCH");
        }

        UUID userId = Uuids.parseOrNull((String) byName.get("id"));
        if (!userRepository.linkAdIdentityIfUnlinked(userId, account.objectGuid(), directory.directory(),
                account.samAccountName())) {
            // Ditautkan oleh masuk lain di saat yang sama: periksa ulang dengan GUID.
            return userRepository.findAdUserByGuid(account.objectGuid()).orElseThrow(() ->
                    ApiException.forbidden("Akun Windows AD ini tidak cocok dengan akun yang didaftarkan. Hubungi "
                            + "administrator.").withCode("AD_IDENTITY_MISMATCH"));
        }
        audit(tenantId, username, "Tautkan identitas Windows AD", username,
                "masuk pertama: " + account.samAccountName() + ", GUID " + account.objectGuid());
        return byName;
    }

    /**
     * Jejak audit masuk. Orang yang belum terdaftar tidak punya penyewa;
     * jejaknya masuk ke penyewa pertama, supaya penolakannya tetap terlihat di
     * Tenant › Audit. Tidak pernah memuat sandi.
     */
    private void audit(UUID tenantId, String username, String action, String target, String detail) {
        try {
            UUID tenant = tenantId != null ? tenantId : tenantRepository.findFirstId().orElse(null);
            if (tenant == null) return;
            auditRepository.insert(tenant, username, AUDIT_COMPONENT, action, target, detail);
        } catch (RuntimeException e) {
            log.warn("Jejak audit masuk Windows AD gagal ditulis.", e);
        }
    }

    private static String describe(AdException.Failure failure) {
        return switch (failure) {
            case INVALID_CREDENTIALS -> "nama atau sandi salah";
            case ACCOUNT_DISABLED -> "akun dinonaktifkan di AD";
            case ACCOUNT_LOCKED -> "akun terkunci di AD";
            case ACCOUNT_EXPIRED -> "akun kedaluwarsa di AD";
            case PASSWORD_EXPIRED -> "sandi AD kedaluwarsa";
            case PASSWORD_MUST_CHANGE -> "sandi AD harus diganti dulu";
            case LOGON_RESTRICTED -> "dibatasi kebijakan masuk AD";
            case UNREACHABLE -> "AD tidak terjangkau";
            case TLS -> "TLS ke AD ditolak (sertifikat)";
            case LOOKUP_UNAVAILABLE, DIRECTORY_ERROR -> "kesalahan direktori";
        };
    }
}
