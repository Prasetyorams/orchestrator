package id.jakforge.forgehub.dto.response;

/**
 * Jawaban masuk. Dibaca Studio, JakRunner, dan dasbor — nama medannya bagian
 * dari kontrak API.
 */
public record LoginResponse(
        String token,
        long expiresInMinutes,
        String userId,
        String username,
        String displayName,
        String role,
        String tenantId,
        String tenantName) {
}
