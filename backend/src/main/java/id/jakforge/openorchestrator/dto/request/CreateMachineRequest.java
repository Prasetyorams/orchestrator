package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import id.jakforge.openorchestrator.model.MachineTypes;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Mesin baru (POST /api/machines).
 *
 * @param runtimes tipe runtime → jumlah; tanpa medan ini mesin mendapat satu runtime Production
 */
public record CreateMachineRequest(
        @NotNull(message = "Nama mesin wajib diisi.")
        String name,
        String type,
        String licenseKey,
        String description,
        Map<String, Integer> runtimes) {

    public CreateMachineRequest {
        name = Strings.trimToNull(name);
        type = Strings.defaultIfEmpty(type, MachineTypes.STANDARD);
        licenseKey = Strings.emptyToNull(licenseKey);
        description = Strings.emptyToNull(description);
    }
}
