package id.jakforge.openorchestrator.security;

import id.jakforge.openorchestrator.common.ApiException;

/**
 * Apakah peran seseorang mengizinkan sebuah tindakan, mis. "folders.update".
 *
 * <p>Antarmuka, bukan langsung {@link PermissionService}: layanan yang
 * memakainya bisa diuji dengan aturan sederhana tanpa basis data —
 * {@code (principal, permission) -> true}.
 */
@FunctionalInterface
public interface PermissionChecker {

    boolean isAllowed(OpenOrchestratorPrincipal principal, String permission);

    /** Melempar 403 kalau peran pemintanya tidak punya izin itu. */
    default void require(OpenOrchestratorPrincipal principal, String permission) {
        if (!isAllowed(principal, permission)) throw denied(permission);
    }

    /**
     * Untuk endpoint "simpan" yang membuat ATAU mengubah (POST /api/assets,
     * POST /api/processes, ...): "create" untuk yang baru, "update" untuk yang
     * sudah ada.
     *
     * <p>Pencegat izin hanya melihat jalurnya, jadi di sana cukup "boleh
     * membuat atau mengubah"; yang tahu mana yang sebenarnya terjadi adalah
     * layanannya, sesudah ia memeriksa apakah namanya sudah ada.
     */
    default void requireSave(OpenOrchestratorPrincipal principal, String resource, boolean alreadyExists) {
        require(principal, Permissions.of(resource, alreadyExists ? Permissions.UPDATE : Permissions.CREATE));
    }

    static ApiException denied(String permission) {
        return ApiException.forbidden("Peran Anda tidak punya izin '" + permission + "'.");
    }
}
