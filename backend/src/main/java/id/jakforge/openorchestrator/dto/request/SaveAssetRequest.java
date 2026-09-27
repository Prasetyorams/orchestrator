package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import jakarta.validation.constraints.NotNull;

/**
 * Membuat atau memperbarui aset (POST /api/assets).
 *
 * <p>Tipe dan bentuk nilainya diperiksa layanan, karena bergantung satu sama
 * lain — "Integer" harus berisi bilangan bulat, "Bool" true atau false.
 *
 * @param username hanya berarti untuk aset bertipe Credential; tipe lain mengabaikannya
 * @param value    untuk Credential: kata sandinya. Kosong pada Credential dan
 *                 Secret yang disunting berarti "biarkan yang lama".
 * @param folderId folder tempat aset BARU dibuat; kosong berarti folder bawaan
 */
public record SaveAssetRequest(
        @NotNull(message = "Nama aset wajib diisi.")
        String name,
        String type,
        String username,
        String value,
        String description,
        String scope,
        String folderId) {

    static final String DEFAULT_TYPE = "Text";
    public static final String DEFAULT_SCOPE = "Global";

    public SaveAssetRequest {
        name = Strings.trimToNull(name);
        type = Strings.defaultIfEmpty(type, DEFAULT_TYPE);
        username = Strings.emptyToNull(username);
        value = Strings.emptyToNull(value);
        description = Strings.emptyToNull(description);
        scope = Strings.defaultIfEmpty(scope, DEFAULT_SCOPE);
        folderId = Strings.emptyToNull(folderId);
    }
}
