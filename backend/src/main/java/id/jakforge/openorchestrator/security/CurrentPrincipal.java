package id.jakforge.openorchestrator.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Siapa yang sedang memanggil, untuk kode di luar controller.
 *
 * <p>Controller tidak memakai ini: di sana pemanggilnya disuntikkan sebagai
 * parameter lewat {@code @AuthenticationPrincipal}. Yang memakainya adalah
 * pencegat izin dan audit, yang berjalan sebelum dan sesudah controller.
 */
public final class CurrentPrincipal {

    private CurrentPrincipal() {
    }

    /** Pemanggil yang sudah dikenali, atau kosong untuk permintaan tanpa token yang sah. */
    public static Optional<OpenOrchestratorPrincipal> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        return authentication != null && authentication.getPrincipal() instanceof OpenOrchestratorPrincipal principal
                ? Optional.of(principal)
                : Optional.empty();
    }
}
