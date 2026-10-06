package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Map;

/**
 * Ubah setelan mesin (PUT /api/machines/{name}). Medan yang tidak dikirim tidak diubah.
 *
 * @param slots        bentuk lama dari {@code runtimes}: sekian runtime Production. Diabaikan kalau
 *                     {@code runtimes} dikirim.
 * @param leaseSeconds berapa lama penyiapan sesi boleh berlangsung tanpa kabar dari agent
 * @param runtimes     tipe runtime → jumlah, mis. {"Production": 2, "Testing": 1}. Menggantikan
 *                     seluruh runtime mesin; tipe yang tidak disebut atau bernilai 0 dihapus.
 *                     Jumlahnya menjadi batas job bersamaan di mesin ini, yang dibatasi lagi oleh
 *                     jumlah sesi interaktif yang dilaporkan Windows.
 * @param state        Active, Maintenance (sementara tidak mengambil job baru), atau Disabled
 *                     (tidak dipakai sama sekali) — V12
 */
public record UpdateMachineRequest(
        String type,
        String description,
        @Min(value = 1, message = "Slot minimal 1.")
        @Max(value = 50, message = "Slot paling banyak 50.")
        Integer slots,
        @Min(value = 30, message = "Lease minimal 30 detik.")
        @Max(value = 3600, message = "Lease paling lama 3600 detik.")
        Integer leaseSeconds,
        Map<String, Integer> runtimes,
        String state) {

    public UpdateMachineRequest {
        type = Strings.emptyToNull(type);
        state = Strings.emptyToNull(state);
    }
}
