package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.Strings;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Antrean baru (POST /api/queues).
 *
 * @param folderId kosong berarti folder bawaan
 */
public record CreateQueueRequest(
        @NotNull(message = "Nama antrean wajib diisi.")
        String name,

        String description,

        @PositiveOrZero(message = "Jumlah percobaan ulang tidak boleh negatif.")
        Integer maxRetries,

        Boolean acceptDuplicates,
        String folderId) {

    static final int DEFAULT_MAX_RETRIES = 3;

    public CreateQueueRequest {
        name = Strings.trimToNull(name);
        description = Strings.emptyToNull(description);
        maxRetries = maxRetries == null ? DEFAULT_MAX_RETRIES : maxRetries;
        acceptDuplicates = acceptDuplicates != null && acceptDuplicates;
        folderId = Strings.emptyToNull(folderId);
    }
}
