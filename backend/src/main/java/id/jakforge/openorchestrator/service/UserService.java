package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.dto.request.SaveUserRequest;
import id.jakforge.openorchestrator.dto.response.AdLookupResponse;
import id.jakforge.openorchestrator.dto.response.SaveResponse;
import id.jakforge.openorchestrator.model.RoleNames;
import id.jakforge.openorchestrator.repository.FolderRepository;
import id.jakforge.openorchestrator.repository.UserRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.Passwords;
import id.jakforge.openorchestrator.security.PermissionService;
import id.jakforge.openorchestrator.security.Permissions;
import id.jakforge.openorchestrator.security.ad.ActiveDirectory;
import id.jakforge.openorchestrator.security.ad.AdAccount;
import id.jakforge.openorchestrator.security.ad.AdException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Aturan tentang pengguna. */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    /** users.auth_provider (V19). */
    static final String LOCAL = "LOCAL";
    static final String AD = "AD";
    static final String AUDIT_COMPONENT = "Pengguna";

    /** Panjang minimal kata sandi yang diisikan pengelola pengguna. */
    static final int MIN_PASSWORD_LENGTH = 6;

    private static final String LAST_ADMINISTRATOR = "Ini satu-satunya Administrator yang tersisa.";

    private final UserRepository userRepository;
    private final FolderRepository folderRepository;
    private final RoleService roleService;
    private final PermissionService permissionService;
    private final AssistantSignInService assistantSignInService;
    private final DashboardSessionService dashboardSessionService;
    private final ActiveDirectory directory;
    private final AuditService auditService;

    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal) {
        return userRepository.findAll(principal.tenantId());
    }

    /**
     * Simpan pengguna.
     *
     * <p>Pembatasan diperiksa DI SINI, bukan diserahkan ke antarmuka: tombol
     * yang disembunyikan tetap bisa dilewati siapa pun yang memanggil API-nya
     * langsung.
     *
     * <ul>
     *   <li>Membuat butuh users.create, mengubah butuh users.update.</li>
     *   <li>Perannya harus ada, dan tidak boleh lebih luas dari peran yang
     *       menyimpannya — begitu juga peran LAMA pengguna yang disunting.
     *       Tanpa itu, pengelola pengguna bisa mengangkat dirinya sendiri atau
     *       temannya menjadi Administrator.</li>
     *   <li>Administrator aktif terakhir tidak bisa diturunkan atau
     *       dinonaktifkan: penyewa tanpa Administrator tidak bisa diurus lagi.</li>
     * </ul>
     */
    @Transactional
    public SaveResponse save(OpenOrchestratorPrincipal principal, SaveUserRequest request) {
        UUID tenantId = principal.tenantId();
        boolean alreadyExists = userRepository.existsByUsername(tenantId, request.username());

        permissionService.require(principal, alreadyExists ? Permissions.USERS_UPDATE : Permissions.USERS_CREATE);

        String newRole = request.role() != null
                ? roleService.resolveGrantableRole(principal, request.role())
                : alreadyExists ? null : roleService.resolveGrantableRole(principal, RoleNames.AUTOMATION_USER);

        if (alreadyExists) {
            updateExistingUser(principal, request, newRole);
        } else {
            createUser(principal, request, newRole);
        }

        // Peran atau status aktif yang baru berlaku sekarang, bukan sesudah
        // ingatan izin kedaluwarsa (lihat PermissionService).
        permissionService.invalidateCache();

        return SaveResponse.of(!alreadyExists);
    }

    private void updateExistingUser(OpenOrchestratorPrincipal principal, SaveUserRequest request, String newRole) {
        UUID tenantId = principal.tenantId();
        String currentRole = userRepository.findRole(tenantId, request.username()).orElse(null);

        roleService.requireGrantable(principal, currentRole);

        Map<String, Object> identity = userRepository.findAuthIdentity(tenantId, request.username()).orElse(Map.of());
        String currentProvider = identity.get("authProvider") == null ? LOCAL : (String) identity.get("authProvider");
        String targetProvider = providerOf(request.authProvider(), currentProvider);
        boolean wasActive = !Boolean.FALSE.equals(identity.get("isActive"));

        boolean remainsAdministrator = request.isActive()
                && RoleNames.ADMINISTRATOR.equals(newRole != null ? newRole : currentRole);

        if (!remainsAdministrator && userRepository.isActiveAdministrator(tenantId, request.username())
                && userRepository.countActiveAdministrators(tenantId) <= 1) {
            throw ApiException.badRequest(LAST_ADMINISTRATOR);
        }

        boolean identityChanged = applyIdentity(principal, request, identity, currentProvider, targetProvider);

        userRepository.update(tenantId, request.username(), request.displayName(), request.email(), newRole,
                request.isActive(), principal.username());

        if (newRole != null && !newRole.equals(currentRole)) {
            audit(principal, "Ubah peran", request.username(), currentRole + " → " + newRole);
        }
        if (wasActive != request.isActive()) {
            audit(principal, request.isActive() ? "Aktifkan" : "Nonaktifkan", request.username(), null);
        }

        // Metode masuk atau identitas AD berganti: siapa pun yang memegang
        // token lamanya bukan lagi "orang ini" yang baru didaftarkan.
        if (identityChanged) {
            assistantSignInService.revokeAllOf(tenantId, request.username(), "identity_changed");
            dashboardSessionService.revokeAllOf(tenantId, request.username(), "identity_changed", null);
        }

        // Kata sandi hanya diganti kalau memang dikirim. Tanpa syarat ini,
        // menyunting alamat surel akan diam-diam mengosongkan sandinya —
        // dan yang bersangkutan baru tahu saat gagal masuk besok pagi.
        // (Akun yang baru dikembalikan ke akun lokal sudah mendapat sandinya.)
        if (request.password() != null && LOCAL.equals(targetProvider) && LOCAL.equals(currentProvider)) {
            if (request.password().length() < MIN_PASSWORD_LENGTH) {
                throw ApiException.badRequest("Kata sandi minimal " + MIN_PASSWORD_LENGTH + " karakter.");
            }

            userRepository.updatePasswordHashByUsername(tenantId, request.username(),
                    Passwords.hash(request.password()));

            // Sandi direset — biasanya karena dikhawatirkan bocor. Open
            // Assistant yang tersambung atas namanya ikut terputus.
            assistantSignInService.revokeAllOf(tenantId, request.username(), "password_reset");
            dashboardSessionService.revokeAllOf(tenantId, request.username(), "password_reset", null);
        }

        if (!request.isActive()) {
            assistantSignInService.revokeAllOf(tenantId, request.username(), "user_deactivated");
            dashboardSessionService.revokeAllOf(tenantId, request.username(), "user_deactivated", null);
        }
    }

    private void createUser(OpenOrchestratorPrincipal principal, SaveUserRequest request, String role) {
        UUID tenantId = principal.tenantId();

        if (AD.equals(providerOf(request.authProvider(), LOCAL))) {
            AdIdentity id = resolveAdIdentity(principal, request, null);
            userRepository.insertAd(tenantId, request.username(), id.adUsername(), id.objectGuid(), directory.directory(),
                    firstNonNull(request.displayName(), id.displayName(), request.username()),
                    firstNonNull(request.email(), id.email()), role, request.isActive(), principal.username());
            audit(principal, "Daftarkan pengguna Windows AD", request.username(), describe(id) + "; peran " + role);
            return;
        }

        if (request.password() == null || request.password().length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.badRequest(
                    "Pengguna baru butuh kata sandi minimal " + MIN_PASSWORD_LENGTH + " karakter.");
        }

        userRepository.insert(tenantId, request.username(), Passwords.hash(request.password()),
                request.displayName() == null ? request.username() : request.displayName(),
                request.email(), role, request.isActive(), principal.username());
    }

    // -----------------------------------------------------------------
    // Akun Windows AD (V19)
    // -----------------------------------------------------------------

    /** Identitas AD yang akan disimpan. {@code objectGuid} null = ditautkan saat masuk pertama (tanpa akun layanan). */
    record AdIdentity(String adUsername, UUID objectGuid, String displayName, String email) {
    }

    /**
     * Metode masuk berganti, atau identitas AD-nya: terapkan, catat.
     *
     * @return true kalau identitasnya berganti — token yang ada harus berhenti
     */
    private boolean applyIdentity(OpenOrchestratorPrincipal principal, SaveUserRequest request,
                                  Map<String, Object> identity, String currentProvider, String targetProvider) {
        UUID tenantId = principal.tenantId();
        String username = request.username();

        if (LOCAL.equals(targetProvider)) {
            if (!AD.equals(currentProvider)) return false;

            if (request.password() == null || request.password().length() < MIN_PASSWORD_LENGTH) {
                throw ApiException.badRequest("Akun yang dikembalikan ke akun lokal butuh kata sandi minimal "
                        + MIN_PASSWORD_LENGTH + " karakter.");
            }
            userRepository.setLocalIdentity(tenantId, username, Passwords.hash(request.password()), principal.username());
            audit(principal, "Ubah metode masuk ke akun lokal", username, "tautan Windows AD "
                    + identity.get("adUsername") + " dilepas");
            return true;
        }

        if (request.password() != null) {
            throw ApiException.badRequest("Pengguna Windows AD tidak memakai kata sandi lokal; kosongkan isian kata sandi.");
        }

        String currentAd = (String) identity.get("adUsername");
        String requested = ActiveDirectory.normalizeUsername(request.adUsername() != null ? request.adUsername()
                : AD.equals(currentProvider) ? currentAd : username);
        boolean switching = !AD.equals(currentProvider);
        boolean renamed = !switching && requested != null && !requested.equalsIgnoreCase(currentAd);
        // Didaftarkan tanpa GUID (atau tautannya dilepas), dan kini akun layanan tersedia: tautkan sekarang.
        boolean linkNow = !switching && !renamed && identity.get("adObjectGuid") == null && directory.canLookUp();

        if (!switching && !renamed && !linkNow) return false;

        AdIdentity id = resolveAdIdentity(principal, request.withAdUsername(requested), username);
        userRepository.setAdIdentity(tenantId, username, id.adUsername(), id.objectGuid(), directory.directory(),
                principal.username());

        if (switching) {
            audit(principal, "Ubah metode masuk ke Windows AD", username, describe(id) + "; sandi lokal dihapus");
            return true;
        }
        if (renamed) {
            audit(principal, "Ubah identitas Windows AD", username, currentAd + " → " + describe(id));
            return true;
        }
        audit(principal, "Tautkan identitas Windows AD", username, describe(id));
        return false;
    }

    /**
     * Identitas AD untuk didaftarkan: nama yang sah, tidak dipakai pengguna
     * lain, dan — kalau ada akun layanan — akun yang benar-benar ada dan aktif
     * di direktori, dengan objectGUID-nya.
     */
    private AdIdentity resolveAdIdentity(OpenOrchestratorPrincipal principal, SaveUserRequest request, String exceptUsername) {
        if (!directory.enabled()) {
            throw ApiException.badRequest("Masuk dengan Windows AD belum diaktifkan di server (AD_AUTH_ENABLED).")
                    .withCode("AD_DISABLED");
        }
        if (request.password() != null) {
            throw ApiException.badRequest("Pengguna Windows AD tidak memakai kata sandi lokal; kosongkan isian kata sandi.");
        }

        String typed = request.adUsername() != null ? request.adUsername() : request.username();
        String sam = ActiveDirectory.normalizeUsername(typed);
        if (sam == null) throw ApiException.badRequest("Nama pengguna Windows AD tidak sah: '" + typed + "'.");

        UUID guid = null;
        String displayName = null;
        String email = null;

        if (directory.canLookUp()) {
            AdAccount account;
            try {
                account = directory.lookUp(sam).orElseThrow(() ->
                        ApiException.badRequest("Akun Windows AD '" + sam + "' tidak ditemukan di direktori.").withCode("AD_NOT_FOUND"));
            } catch (AdException e) {
                log.warn("Mencari akun Windows AD '{}' gagal: {} — {}", sam, e.failure(), e.getMessage());
                throw ApiException.unavailable("Windows AD tidak dapat dihubungi untuk mencari akun. Coba lagi nanti.")
                        .withCode("AD_UNAVAILABLE");
            }
            if (!account.enabled()) {
                throw ApiException.badRequest("Akun Windows AD '" + sam + "' dinonaktifkan di direktori.").withCode("AD_ACCOUNT_DISABLED");
            }
            guid = account.objectGuid();
            displayName = account.displayName();
            email = account.email();
            String canonical = account.samAccountName();

            String other = userRepository.findUsernameUsingAdIdentity(canonical, guid, principal.tenantId(), exceptUsername).orElse(null);
            if (other != null) throw identityInUse(canonical, other);
            return new AdIdentity(canonical, guid, displayName, email);
        }

        String other = userRepository.findUsernameUsingAdIdentity(sam, null, principal.tenantId(), exceptUsername).orElse(null);
        if (other != null) throw identityInUse(sam, other);
        return new AdIdentity(sam, null, null, null);
    }

    /** Lepas tautan AD: GUID dikosongkan; token yang ada berhenti; ditautkan lagi saat masuk berikutnya atau saat disimpan dengan akun layanan. */
    @Transactional
    public void unlinkAd(OpenOrchestratorPrincipal principal, String username) {
        UUID tenantId = principal.tenantId();
        permissionService.require(principal, Permissions.USERS_UPDATE);
        roleService.requireGrantable(principal, userRepository.findRole(tenantId, username).orElse(null));

        Map<String, Object> identity = userRepository.findAuthIdentity(tenantId, username)
                .orElseThrow(() -> ApiException.notFound("Pengguna tidak ada."));
        if (!AD.equals(identity.get("authProvider"))) {
            throw ApiException.badRequest("Pengguna ini bukan akun Windows AD.");
        }
        if (identity.get("adObjectGuid") == null) {
            throw ApiException.badRequest("Pengguna ini belum tertaut ke akun Windows AD.");
        }

        userRepository.unlinkAdIdentity(tenantId, username, principal.username());
        assistantSignInService.revokeAllOf(tenantId, username, "ad_unlinked");
        dashboardSessionService.revokeAllOf(tenantId, username, "ad_unlinked", null);
        audit(principal, "Lepas tautan Windows AD", username, identity.get("adUsername") + ", GUID " + identity.get("adObjectGuid")
                + "; ditautkan lagi saat masuk berikutnya");
    }

    /**
     * Cari akun di AD untuk dialog Pengguna. Tanpa {@code username}: hanya
     * apakah AD aktif dan apakah pencarian tersedia (akun layanan).
     */
    public AdLookupResponse lookUpAd(OpenOrchestratorPrincipal principal, String username) {
        if (!directory.enabled()) return AdLookupResponse.unavailable(false, false, directory.label());
        if (!directory.canLookUp() || username == null || username.isBlank()) {
            return AdLookupResponse.unavailable(true, directory.canLookUp(), directory.label());
        }

        String sam = ActiveDirectory.normalizeUsername(username);
        if (sam == null) throw ApiException.badRequest("Nama pengguna Windows AD tidak sah: '" + username + "'.");

        AdAccount account;
        try {
            account = directory.lookUp(sam).orElse(null);
        } catch (AdException e) {
            log.warn("Mencari akun Windows AD '{}' gagal: {} — {}", sam, e.failure(), e.getMessage());
            throw ApiException.unavailable("Windows AD tidak dapat dihubungi untuk mencari akun. Coba lagi nanti.")
                    .withCode("AD_UNAVAILABLE");
        }
        if (account == null) return AdLookupResponse.notFound(directory.label());

        String linkedTo = userRepository.findUsernameUsingAdIdentity(account.samAccountName(), account.objectGuid(),
                principal.tenantId(), null).orElse(null);
        return AdLookupResponse.found(directory.label(), account, linkedTo);
    }

    private static String providerOf(String requested, String fallback) {
        if (requested == null) return fallback;
        String p = requested.toUpperCase(Locale.ROOT);
        if (p.equals(LOCAL) || p.equals(AD)) return p;
        throw ApiException.badRequest("Metode masuk tidak dikenal: '" + requested + "'. Pilih LOCAL atau AD.");
    }

    private static ApiException identityInUse(String sam, String other) {
        return ApiException.conflict("Akun Windows AD '" + sam + "' sudah dipakai pengguna '" + other + "'.")
                .withCode("AD_IDENTITY_IN_USE");
    }

    private static String describe(AdIdentity id) {
        return "AD " + id.adUsername() + (id.objectGuid() != null ? ", GUID " + id.objectGuid() : ", ditautkan saat masuk pertama");
    }

    private static String firstNonNull(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private void audit(OpenOrchestratorPrincipal principal, String action, String target, String detail) {
        try {
            auditService.record(principal, AUDIT_COMPONENT, action, target, detail);
        } catch (RuntimeException e) {
            log.warn("Jejak audit '{}' gagal ditulis.", action, e);
        }
    }

    @Transactional
    public void delete(OpenOrchestratorPrincipal principal, String username) {
        // Menghapus diri sendiri akan mengunci orangnya keluar dari OpenOrchestrator
        // miliknya sendiri, dan tidak ada jalan masuk lain untuk membatalkannya.
        if (username.equalsIgnoreCase(principal.username())) {
            throw ApiException.badRequest("Tidak bisa menghapus akun yang sedang dipakai.");
        }

        UUID tenantId = principal.tenantId();

        roleService.requireGrantable(principal, userRepository.findRole(tenantId, username).orElse(null));

        // Penyewa tanpa satu pun administrator aktif tidak bisa diurus lagi:
        // tidak ada yang bisa membuat administrator baru, dan tidak ada pintu
        // belakang untuk memperbaikinya.
        if (userRepository.isAdministrator(tenantId, username)
                && userRepository.countActiveAdministrators(tenantId) <= 1) {
            throw ApiException.badRequest(LAST_ADMINISTRATOR);
        }

        deletePersonalFolder(tenantId, username);
        assistantSignInService.revokeAllOf(tenantId, username, "user_deleted");
        dashboardSessionService.revokeAllOf(tenantId, username, "user_deleted", null);

        if (userRepository.deleteByUsername(tenantId, username) == 0) {
            throw ApiException.notFound("Pengguna tidak ada.");
        }

        permissionService.invalidateCache();
    }

    /**
     * Folder Saya milik pengguna yang akan dihapus.
     *
     * <p>Yang kosong ikut dihapus; riwayat pekerjaannya pindah ke folder
     * bawaan supaya tetap bisa dibaca. Yang masih berisi MENGHENTIKAN
     * penghapusan: proses dan aset di sana bisa saja masih dijalankan robot,
     * dan tidak ada orang lain yang bisa melihat folder itu untuk
     * menyelamatkan isinya.
     */
    private void deletePersonalFolder(UUID tenantId, String username) {
        UUID userId = userRepository.findIdByUsername(tenantId, username).orElse(null);
        if (userId == null) return;

        Map<String, Object> personalFolder = folderRepository.findPersonal(tenantId, userId).orElse(null);
        if (personalFolder == null) return;

        UUID folderId = Uuids.parseOrNull((String) personalFolder.get("id"));

        if (folderRepository.countContents(tenantId, folderId) > 0) {
            throw ApiException.badRequest("Folder Saya milik '" + username
                    + "' masih berisi proses, pemicu, antrean, aset, atau ember penyimpanan. Pindahkan atau hapus isinya dulu.");
        }

        folderRepository.moveJobs(tenantId, folderId, folderRepository.findDefaultFolderId(tenantId));
        folderRepository.delete(tenantId, folderId);
    }
}
