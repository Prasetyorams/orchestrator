package id.jakforge.openorchestrator.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * {@code {"ok": true, "id": "...", "ids": [...]}} — job yang baru dijadwalkan.
 *
 * <p>{@code id} tetap ada dan berisi job PERTAMA: Studio (activity Start Job)
 * membaca medan itu untuk menunggu job-nya selesai, dan ia hanya pernah
 * meminta satu. {@code ids} berisi semuanya, untuk "jalankan N kali".
 */
public record JobsCreatedResponse(boolean ok, String id, List<String> ids) {

    public static JobsCreatedResponse of(List<UUID> jobIds) {
        List<String> ids = jobIds.stream().map(UUID::toString).toList();
        return new JobsCreatedResponse(true, ids.getFirst(), ids);
    }
}
