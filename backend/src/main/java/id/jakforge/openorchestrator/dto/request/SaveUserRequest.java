package id.jakforge.openorchestrator.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import id.jakforge.openorchestrator.common.Strings;
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
 * @param isActive     tidak dikirim berarti aktif
 * @param authProvider LOCAL atau AD (V19); kosong = akun lokal untuk pengguna baru, tidak berubah saat menyunting
 * @param adUsername   nama Windows AD (sAMAccountName) untuk authProvider AD; kosong = sama dengan username
 */
public record SaveUserRequest(
        @NotNull(message = "Nama pengguna wajib diisi.")
        String username,
        String password,
        String displayName,
        String email,
        String role,
        @JsonProperty("isActive") Boolean isActive,
        String authProvider,
        String adUsername) {

    public SaveUserRequest(String username, String password, String displayName, String email, String role,
                           Boolean isActive) {
        this(username, password, displayName, email, role, isActive, null, null);
    }

    public SaveUserRequest {
        username = Strings.trimToNull(username);
        password = Strings.emptyToNull(password);
        displayName = Strings.emptyToNull(displayName);
        email = Strings.emptyToNull(email);
        role = Strings.emptyToNull(role);
        isActive = isActive == null || isActive;
        authProvider = Strings.trimToNull(authProvider);
        adUsername = Strings.trimToNull(adUsername);
    }

    public SaveUserRequest withAdUsername(String value) {
        return new SaveUserRequest(username, password, displayName, email, role, isActive, authProvider, value);
    }
}
