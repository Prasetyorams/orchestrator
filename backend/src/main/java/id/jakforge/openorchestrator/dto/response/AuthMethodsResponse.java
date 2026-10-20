package id.jakforge.openorchestrator.dto.response;

/**
 * Cara masuk yang tersedia, untuk layar masuk (GET /api/auth/methods, tanpa token, V19).
 * Sengaja tidak menyebut server, domain, atau OU apa pun.
 */
public record AuthMethodsResponse(boolean local, Ad ad) {

    /** @param label nama tombolnya, mis. "Windows AD" (AD_LABEL) */
    public record Ad(boolean enabled, String label) {
    }
}
