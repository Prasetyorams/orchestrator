package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.Strings;
import jakarta.validation.constraints.NotNull;

/** Menugaskan robot ke folder (POST /api/folders/{id}/robots). */
public record AssignRobotRequest(
        @NotNull(message = "robotName wajib diisi.")
        String robotName) {

    public AssignRobotRequest {
        robotName = Strings.trimToNull(robotName);
    }
}
