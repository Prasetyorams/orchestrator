package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.Strings;
import jakarta.validation.constraints.NotNull;

/**
 * Menyimpan kredensial lewat jalur lama (POST /api/credentials).
 *
 * <p>Sejak V3 kredensial adalah aset bertipe Credential; jalur ini tetap ada
 * untuk klien lama.
 *
 * @param password kosong pada kredensial yang disunting berarti "biarkan yang lama"
 */
public record SaveCredentialRequest(
        @NotNull(message = "Nama kredensial wajib diisi.")
        String name,
        String username,
        String password,
        String description) {

    public SaveCredentialRequest {
        name = Strings.trimToNull(name);
        username = Strings.emptyToNull(username);
        password = Strings.emptyToNull(password);
        description = Strings.emptyToNull(description);
    }
}
