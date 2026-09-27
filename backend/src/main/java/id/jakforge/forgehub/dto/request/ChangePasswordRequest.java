package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.Strings;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Ganti kata sandi sendiri (POST /api/auth/password).
 *
 * <p>Kata sandi TIDAK dipangkas: spasi di awal atau akhir adalah bagian dari
 * kata sandinya.
 */
public record ChangePasswordRequest(
        @NotNull(message = "Kata sandi saat ini wajib diisi.")
        String currentPassword,

        @NotNull(message = "Kata sandi baru minimal 8 karakter.")
        @Size(min = ChangePasswordRequest.MIN_NEW_PASSWORD_LENGTH, message = "Kata sandi baru minimal {min} karakter.")
        String newPassword) {

    public static final int MIN_NEW_PASSWORD_LENGTH = 8;

    public ChangePasswordRequest {
        currentPassword = Strings.emptyToNull(currentPassword);
        newPassword = Strings.emptyToNull(newPassword);
    }
}
