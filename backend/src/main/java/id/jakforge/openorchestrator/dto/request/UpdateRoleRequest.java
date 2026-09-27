package id.jakforge.openorchestrator.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import id.jakforge.openorchestrator.common.FlexibleStringListDeserializer;
import id.jakforge.openorchestrator.common.Strings;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Mengubah peran (PUT /api/roles/{name}).
 *
 * @param name        kosong berarti namanya tetap; berbeda dari yang di jalur berarti ganti nama
 * @param permissions null berarti izinnya tidak diubah
 */
public record UpdateRoleRequest(
        @Size(max = RoleRequests.MAX_NAME_LENGTH, message = "Nama peran paling panjang {max} karakter.")
        String name,

        @Size(max = RoleRequests.MAX_DESCRIPTION_LENGTH, message = "Keterangan paling panjang {max} karakter.")
        String description,

        @JsonDeserialize(using = FlexibleStringListDeserializer.class)
        List<String> permissions) {

    public UpdateRoleRequest {
        name = Strings.trimToNull(name);
        description = Strings.trimToNull(description);
    }
}
