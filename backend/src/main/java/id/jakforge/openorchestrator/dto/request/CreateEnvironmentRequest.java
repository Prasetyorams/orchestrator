package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
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
