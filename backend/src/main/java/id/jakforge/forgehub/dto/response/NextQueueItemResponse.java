package id.jakforge.forgehub.dto.response;

import java.util.Map;

/** Butir antrean berikutnya untuk robot; {@code {"item": null}} kalau antreannya kosong. */
public record NextQueueItemResponse(Map<String, Object> item) {
}
