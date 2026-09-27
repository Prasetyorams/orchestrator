package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.Strings;
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
