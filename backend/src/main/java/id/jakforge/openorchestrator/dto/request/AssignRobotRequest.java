package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import jakarta.validation.constraints.NotNull;

/** Menugaskan robot ke folder (POST /api/folders/{id}/robots). */
public record AssignRobotRequest(
        @NotNull(message = "robotName wajib diisi.")
        String robotName) {

    public AssignRobotRequest {
        robotName = Strings.trimToNull(robotName);
    }
}
