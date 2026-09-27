package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import id.jakforge.openorchestrator.model.MachineTypes;
import jakarta.validation.constraints.NotNull;

/** Mesin baru (POST /api/machines). */
public record CreateMachineRequest(
        @NotNull(message = "Nama mesin wajib diisi.")
        String name,
        String type,
        String licenseKey,
        String description) {

    public CreateMachineRequest {
        name = Strings.trimToNull(name);
        type = Strings.defaultIfEmpty(type, MachineTypes.STANDARD);
        licenseKey = Strings.emptyToNull(licenseKey);
        description = Strings.emptyToNull(description);
    }
}
