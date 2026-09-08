package id.jakforge.forgehub.auth;

import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "username wajib diisi") String username,
            @NotBlank(message = "password wajib diisi") String password) {
    }

    public record LoginResponse(
            String token,
            long expiresInMinutes,
            String username,
            String fullName,
            String role,
            String tenantId) {
    }

    public record MeResponse(
            String userId,
            String tenantId,
            String username,
            String role) {
    }
}
