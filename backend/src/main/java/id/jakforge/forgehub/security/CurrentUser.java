package id.jakforge.forgehub.security;

import org.springframework.security.core.context.SecurityContextHolder;

/** Pintasan membaca siapa yang sedang memanggil. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static ForgeHubPrincipal get() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) throw new IllegalStateException("Tidak ada pengguna yang terautentikasi.");

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof ForgeHubPrincipal p)) {
            throw new IllegalStateException("Tidak ada pengguna yang terautentikasi.");
        }

        return p;
    }
}
