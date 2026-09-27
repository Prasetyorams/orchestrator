package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.Strings;
import jakarta.validation.constraints.NotNull;

/** Menugaskan pengguna ke folder (POST /api/folders/{id}/users). */
public record AssignUserRequest(
        @NotNull(message = "username wajib diisi.")
        String username) {

    public AssignUserRequest {
        username = Strings.trimToNull(username);
    }
}
