package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import jakarta.validation.constraints.NotNull;

/** Menugaskan pengguna ke folder (POST /api/folders/{id}/users). */
public record AssignUserRequest(
        @NotNull(message = "username wajib diisi.")
        String username) {

    public AssignUserRequest {
        username = Strings.trimToNull(username);
    }
}
