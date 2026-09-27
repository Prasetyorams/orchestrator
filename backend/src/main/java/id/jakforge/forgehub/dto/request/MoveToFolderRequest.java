package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.Strings;
import jakarta.validation.constraints.NotNull;

/**
 * Memindahkan proses, antrean, aset, atau ember ke folder lain
 * (PUT /api/.../{name}/folder).
 *
 * @param folderId folder TUJUAN
 */
public record MoveToFolderRequest(
        @NotNull(message = "folderId wajib diisi.")
        String folderId) {

    public MoveToFolderRequest {
        folderId = Strings.trimToNull(folderId);
    }
}
