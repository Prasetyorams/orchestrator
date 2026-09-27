package id.jakforge.openorchestrator.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import id.jakforge.openorchestrator.common.FlexibleStringListDeserializer;
import id.jakforge.openorchestrator.common.Strings;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Peran baru dari layar Peran (POST /api/roles).
 *
 * <p>Nama "Administrator" yang dicadangkan, dan izin yang dikenal serta tidak
 * lebih luas dari milik pembuatnya, diperiksa layanan.
 *
 * @param permissions pola izin — larik JSON, atau untai dipisah koma seperti
 *                    yang tersimpan di basis data
 */
public record CreateRoleRequest(
        @NotNull(message = "Nama peran wajib diisi.")
        @Size(max = RoleRequests.MAX_NAME_LENGTH, message = "Nama peran paling panjang {max} karakter.")
        String name,

        @Size(max = RoleRequests.MAX_DESCRIPTION_LENGTH, message = "Keterangan paling panjang {max} karakter.")
        String description,

        @JsonDeserialize(using = FlexibleStringListDeserializer.class)
        List<String> permissions) {

    public CreateRoleRequest {
        name = Strings.trimToNull(name);
        description = Strings.trimToNull(description);
    }
}
