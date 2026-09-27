package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.Strings;
import jakarta.validation.constraints.NotNull;

/** Lingkungan baru (POST /api/environments). */
public record CreateEnvironmentRequest(
        @NotNull(message = "Nama lingkungan wajib diisi.")
        String name,
        String description) {

    public CreateEnvironmentRequest {
        name = Strings.trimToNull(name);
        description = Strings.emptyToNull(description);
    }
}
