package id.jakforge.forgehub.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import id.jakforge.forgehub.common.Strings;
import jakarta.validation.constraints.NotNull;

/**
 * Membuat atau memperbarui pengguna (POST /api/users).
 *
 * <p>Aturan kata sandinya — wajib untuk pengguna baru, boleh kosong saat
 * menyunting — bergantung pada apakah penggunanya sudah ada, jadi diperiksa
 * layanan.
 *
 * @param password kosong saat menyunting berarti kata sandinya tidak diganti
 * @param role     kosong saat menyunting berarti perannya tidak diganti
 * @param isActive tidak dikirim berarti aktif
 */
public record SaveUserRequest(
        @NotNull(message = "Nama pengguna wajib diisi.")
        String username,
        String password,
        String displayName,
        String email,
        String role,
        @JsonProperty("isActive") Boolean isActive) {

    public SaveUserRequest {
        username = Strings.trimToNull(username);
        password = Strings.emptyToNull(password);
        displayName = Strings.emptyToNull(displayName);
        email = Strings.emptyToNull(email);
        role = Strings.emptyToNull(role);
        isActive = isActive == null || isActive;
    }
}
