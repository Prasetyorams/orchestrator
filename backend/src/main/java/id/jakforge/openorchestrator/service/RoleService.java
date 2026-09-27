package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.dto.request.CreateRoleRequest;
import id.jakforge.openorchestrator.dto.request.UpdateRoleRequest;
import id.jakforge.openorchestrator.dto.response.PermissionResourceResponse;
import id.jakforge.openorchestrator.model.RoleNames;
import id.jakforge.openorchestrator.repository.RoleRepository;
import id.jakforge.openorchestrator.repository.UserRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.PermissionCatalog;
import id.jakforge.openorchestrator.security.PermissionService;
import id.jakforge.openorchestrator.security.Permissions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aturan tentang peran kustom.
 *
 * <p>Tidak ada yang bisa memberi izin yang ia sendiri tidak punya — lewat peran
 * baru, dengan mengubah peran, atau dengan memasang peran pada seseorang.
 * Tanpa itu, siapa pun yang boleh menyunting peran bisa menjadikan dirinya
 * Administrator dalam dua klik.
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private static final String PERMISSION_SEPARATOR = ",";
    private static final String ROLE_NOT_FOUND = "Peran tidak ada.";
    private static final String NAME_COLUMN = "name";
    private static final String PERMISSIONS_COLUMN = "permissions";
    private static final String LOCKED_FLAG = "locked";

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;

    /** {@code locked}: peran Administrator, yang tidak bisa diubah atau dihapus. */
    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal) {
        List<Map<String, Object>> roles = roleRepository.findAll(principal.tenantId());

        for (Map<String, Object> role : roles) role.put(LOCKED_FLAG, RoleNames.ADMINISTRATOR.equals(role.get(NAME_COLUMN)));

        return roles;
    }

    /** Sumber dan tindakan yang bisa dipilih di matriks izin layar Peran. */
    public List<PermissionResourceResponse> permissionCatalog() {
        return PermissionCatalog.RESOURCES.stream()
                .map(resource -> new PermissionResourceResponse(resource.key(), resource.actions()))
                .toList();
    }

    @Transactional
    public void create(OpenOrchestratorPrincipal principal, CreateRoleRequest request) {
        UUID tenantId = principal.tenantId();
        String name = requireUnreservedName(request.name());

        if (roleRepository.findByName(tenantId, name).isPresent()) throw roleExists(name);

        List<String> permissions = grantablePermissions(principal,
                request.permissions() == null ? List.of() : request.permissions());

        roleRepository.insert(tenantId, name, request.description(), String.join(PERMISSION_SEPARATOR, permissions));
    }

    /**
     * Ubah nama, keterangan, atau izin sebuah peran.
     *
     * <p>Ganti nama ikut mengganti peran setiap penggunanya, dalam transaksi
     * yang sama: pengguna menyimpan NAMA perannya, dan pengguna yang perannya
     * tidak lagi ada tidak bisa apa-apa.
     */
    @Transactional
    public void update(OpenOrchestratorPrincipal principal, String roleName, UpdateRoleRequest request) {
        UUID tenantId = principal.tenantId();
        Map<String, Object> role = roleRepository.findByName(tenantId, roleName)
                .orElseThrow(() -> ApiException.notFound(ROLE_NOT_FOUND));

        String currentName = (String) role.get(NAME_COLUMN);

        if (RoleNames.ADMINISTRATOR.equals(currentName)) {
            throw ApiException.badRequest("Peran Administrator tidak bisa diubah.");
        }

        // Yang sedang memegang izin lebih sempit tidak boleh menyunting peran
        // yang lebih luas — bahkan hanya untuk menyempitkannya.
        requireGrantable(principal, currentName);

        String newName = request.name() == null ? currentName : requireUnreservedName(request.name());

        if (!newName.equalsIgnoreCase(currentName) && roleRepository.findByName(tenantId, newName).isPresent()) {
            throw roleExists(newName);
        }

        List<String> permissions = request.permissions() == null
                ? List.copyOf(PermissionCatalog.parsePatterns((String) role.get(PERMISSIONS_COLUMN)))
                : grantablePermissions(principal, request.permissions());

        roleRepository.update(tenantId, currentName, newName, request.description(),
                String.join(PERMISSION_SEPARATOR, permissions));

        if (!newName.equals(currentName)) userRepository.replaceRole(tenantId, currentName, newName);

        permissionService.invalidateCache();
    }

    /** Peran yang masih dipakai tidak bisa dihapus: penggunanya akan tertinggal tanpa izin apa pun. */
    @Transactional
    public void delete(OpenOrchestratorPrincipal principal, String roleName) {
        UUID tenantId = principal.tenantId();
        Map<String, Object> role = roleRepository.findByName(tenantId, roleName)
                .orElseThrow(() -> ApiException.notFound(ROLE_NOT_FOUND));

        String currentName = (String) role.get(NAME_COLUMN);

        if (RoleNames.ADMINISTRATOR.equals(currentName)) {
            throw ApiException.badRequest("Peran Administrator tidak bisa dihapus.");
        }

        requireGrantable(principal, currentName);

        long userCount = userRepository.countByRole(tenantId, currentName);

        if (userCount > 0) {
            throw ApiException.conflict("Peran '" + currentName + "' masih dipakai " + userCount
                    + " pengguna. Ganti peran mereka dulu.");
        }

        roleRepository.delete(tenantId, currentName);
        permissionService.invalidateCache();
    }

    // -----------------------------------------------------------------
    // Dipakai juga oleh UserService
    // -----------------------------------------------------------------

    /** Nama resmi peran yang diminta — harus ada, dan tidak lebih luas dari peran pemintanya. */
    public String resolveGrantableRole(OpenOrchestratorPrincipal principal, String requestedName) {
        String trimmedName = requestedName.trim();
        Map<String, Object> role = roleRepository.findByName(principal.tenantId(), trimmedName)
                .orElseThrow(() -> ApiException.badRequest("Peran '" + trimmedName + "' tidak ada."));

        String name = (String) role.get(NAME_COLUMN);
        requireGrantable(principal, name);

        return name;
    }

    /**
     * Peran itu tidak memberi izin yang tidak dimiliki peran pemintanya.
     * Administrator berarti semuanya, apa pun isi barisnya.
     *
     * @param roleName null berarti tidak ada yang diperiksa
     */
    public void requireGrantable(OpenOrchestratorPrincipal principal, String roleName) {
        if (roleName == null) return;

        Set<String> requested;

        if (RoleNames.ADMINISTRATOR.equals(roleName)) {
            requested = Set.of(Permissions.ALL);
        } else {
            String storedPatterns = roleRepository.findByName(principal.tenantId(), roleName)
                    .map(role -> (String) role.get(PERMISSIONS_COLUMN))
                    .orElse(null);
            requested = PermissionCatalog.parsePatterns(storedPatterns);
        }

        String missing = PermissionCatalog.firstUncovered(permissionService.patternsOf(principal), requested);

        if (missing != null) {
            throw ApiException.forbidden("Peran '" + roleName + "' memuat izin '" + missing
                    + "' yang tidak dimiliki peran Anda.");
        }
    }

    // -----------------------------------------------------------------
    // Alat
    // -----------------------------------------------------------------

    /** Izin dari layar Peran: dikenal, dirapikan, dan tidak lebih luas dari milik pemintanya. */
    private List<String> grantablePermissions(OpenOrchestratorPrincipal principal, Collection<String> requested) {
        List<String> permissions = PermissionCatalog.normalize(requested);
        String missing = PermissionCatalog.firstUncovered(permissionService.patternsOf(principal), permissions);

        if (missing != null) {
            throw ApiException.forbidden("Anda tidak bisa memberi izin '" + missing
                    + "' yang tidak dimiliki peran Anda sendiri.");
        }

        return permissions;
    }

    /** Nama "Administrator" dicadangkan untuk peran bawaan yang terkunci. */
    private static String requireUnreservedName(String name) {
        if (name == null) throw ApiException.badRequest("Nama peran wajib diisi.");
        if (name.equalsIgnoreCase(RoleNames.ADMINISTRATOR)) throw roleExists(RoleNames.ADMINISTRATOR);

        return name;
    }

    private static ApiException roleExists(String name) {
        return ApiException.conflict("Peran '" + name + "' sudah ada.");
    }
}
