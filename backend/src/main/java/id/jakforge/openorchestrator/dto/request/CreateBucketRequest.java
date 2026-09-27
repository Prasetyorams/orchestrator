package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import jakarta.validation.constraints.NotNull;

/**
 * Ember penyimpanan baru (POST /api/buckets).
 *
 * @param folderId kosong berarti folder bawaan
 */
public record CreateBucketRequest(
        @NotNull(message = "Nama ember wajib diisi.")
        String name,
        String description,
        String folderId) {

    public CreateBucketRequest {
        name = Strings.trimToNull(name);
        description = Strings.emptyToNull(description);
        folderId = Strings.emptyToNull(folderId);
    }
}
