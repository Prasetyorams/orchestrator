package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Ubah setelan mesin (PUT /api/machines/{name}). Medan yang tidak dikirim tidak diubah.
 *
 * @param slots        berapa job Robot Agent boleh berjalan bersamaan di mesin ini; dibatasi lagi
 *                     oleh jumlah sesi interaktif yang dilaporkan Windows
 * @param leaseSeconds berapa lama penyiapan sesi boleh berlangsung tanpa kabar dari agent
 */
public record UpdateMachineRequest(
        String type,
        String description,
        @Min(value = 1, message = "Slot minimal 1.")
        @Max(value = 50, message = "Slot paling banyak 50.")
        Integer slots,
        @Min(value = 30, message = "Lease minimal 30 detik.")
        @Max(value = 3600, message = "Lease paling lama 3600 detik.")
        Integer leaseSeconds) {

    public UpdateMachineRequest {
        type = Strings.emptyToNull(type);
    }
}
