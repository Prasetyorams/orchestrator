package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.Strings;
import jakarta.validation.constraints.NotNull;

/**
 * Membuat atau memperbarui proses di sebuah folder (POST /api/processes).
 *
 * <p>Medan yang tidak dikirim tidak menghapus nilai yang sudah ada.
 *
 * @param folderId folder prosesnya; kosong berarti proses bernama itu di mana
 *                 pun ia berada, atau folder bawaan untuk yang baru
 */
public record SaveProcessRequest(
        @NotNull(message = "Nama proses wajib diisi.")
        String name,
        String packageName,
        String packageVersion,
        String environment,
        String description,
        String folderId) {

    public SaveProcessRequest {
        name = Strings.trimToNull(name);
        packageName = Strings.emptyToNull(packageName);
        packageVersion = Strings.emptyToNull(packageVersion);
        environment = Strings.emptyToNull(environment);
        description = Strings.emptyToNull(description);
        folderId = Strings.emptyToNull(folderId);
    }
}
