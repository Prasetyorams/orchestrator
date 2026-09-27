package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import jakarta.validation.constraints.NotNull;

/** Robot baru yang didaftarkan dari dasbor (POST /api/robots). */
public record CreateRobotRequest(
        @NotNull(message = "Nama robot wajib diisi.")
        String name,
        String machineName,
        String username,
        String type,
        String environment,
        String description) {

    static final String DEFAULT_TYPE = "Unattended";
    static final String DEFAULT_ENVIRONMENT = "Production";

    public CreateRobotRequest {
        name = Strings.trimToNull(name);
        machineName = Strings.emptyToNull(machineName);
        username = Strings.emptyToNull(username);
        type = Strings.defaultIfEmpty(type, DEFAULT_TYPE);
        environment = Strings.defaultIfEmpty(environment, DEFAULT_ENVIRONMENT);
        description = Strings.emptyToNull(description);
    }
}
