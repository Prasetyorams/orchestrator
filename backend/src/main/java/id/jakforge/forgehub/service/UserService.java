package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.common.Uuids;
import id.jakforge.forgehub.dto.request.SaveUserRequest;
import id.jakforge.forgehub.dto.response.SaveResponse;
import id.jakforge.forgehub.model.RoleNames;
import id.jakforge.forgehub.repository.FolderRepository;
import id.jakforge.forgehub.repository.UserRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.Passwords;
import id.jakforge.forgehub.security.PermissionService;
import id.jakforge.forgehub.security.Permissions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Aturan tentang pengguna. */
@Service
@RequiredArgsConstructor
public class UserService {

    /** Panjang minimal kata sandi yang diisikan pengelola pengguna. */
    static final int MIN_PASSWORD_LENGTH = 6;

    private static final String LAST_ADMINISTRATOR = "Ini satu-satunya Administrator yang tersisa.";

    private final UserRepository userRepository;
    private final FolderRepository folderRepository;
    private final RoleService roleService;
    private final PermissionService permissionService;

    public List<Map<String, Object>> findAll(ForgeHubPrincipal principal) {
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
    public SaveResponse save(ForgeHubPrincipal principal, SaveUserRequest request) {
        UUID tenantId = principal.tenantId();
        boolean alreadyExists = userRepository.existsByUsername(tenantId, request.username());

        permissionService.require(principal, alreadyExists ? Permissions.USERS_UPDATE : Permissions.USERS_CREATE);

        String newRole = request.role() != null
                ? roleService.resolveGrantableRole(principal, request.role())
                : alreadyExists ? null : roleService.resolveGrantableRole(principal, RoleNames.AUTOMATION_USER);

        if (alreadyExists) {
            updateExistingUser(principal, request, newRole);
        } else {
            createUser(tenantId, request, newRole);
        }

        // Peran atau status aktif yang baru berlaku sekarang, bukan sesudah
        // ingatan izin kedaluwarsa (lihat PermissionService).
        permissionService.invalidateCache();

        return SaveResponse.of(!alreadyExists);
    }

    private void updateExistingUser(ForgeHubPrincipal principal, SaveUserRequest request, String newRole) {
        UUID tenantId = principal.tenantId();
        String currentRole = userRepository.findRole(tenantId, request.username()).orElse(null);

        roleService.requireGrantable(principal, currentRole);

        boolean remainsAdministrator = request.isActive()
                && RoleNames.ADMINISTRATOR.equals(newRole != null ? newRole : currentRole);

        if (!remainsAdministrator && userRepository.isActiveAdministrator(tenantId, request.username())
                && userRepository.countActiveAdministrators(tenantId) <= 1) {
            throw ApiException.badRequest(LAST_ADMINISTRATOR);
        }

        userRepository.update(tenantId, request.username(), request.displayName(), request.email(), newRole,
                request.isActive());

        // Kata sandi hanya diganti kalau memang dikirim. Tanpa syarat ini,
        // menyunting alamat surel akan diam-diam mengosongkan sandinya —
        // dan yang bersangkutan baru tahu saat gagal masuk besok pagi.
        if (request.password() != null) {
            if (request.password().length() < MIN_PASSWORD_LENGTH) {
                throw ApiException.badRequest("Kata sandi minimal " + MIN_PASSWORD_LENGTH + " karakter.");
            }

            userRepository.updatePasswordHashByUsername(tenantId, request.username(),
                    Passwords.hash(request.password()));
        }
    }

    private void createUser(UUID tenantId, SaveUserRequest request, String role) {
        if (request.password() == null || request.password().length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.badRequest(
                    "Pengguna baru butuh kata sandi minimal " + MIN_PASSWORD_LENGTH + " karakter.");
        }

        userRepository.insert(tenantId, request.username(), Passwords.hash(request.password()),
                request.displayName() == null ? request.username() : request.displayName(),
                request.email(), role, request.isActive());
    }

    @Transactional
    public void delete(ForgeHubPrincipal principal, String username) {
        // Menghapus diri sendiri akan mengunci orangnya keluar dari ForgeHub
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
