package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.RequestBodies;

import java.util.Map;

/** Butir baru untuk sebuah antrean (POST /api/queues/{name}/items), dibaca longgar. */
public record AddQueueItemRequest(String reference, String priority, String content) {

    static final String DEFAULT_PRIORITY = "Normal";

    public static AddQueueItemRequest fromBody(Map<String, Object> body) {
        return new AddQueueItemRequest(
                RequestBodies.text(body, "reference"),
                RequestBodies.text(body, "priority", DEFAULT_PRIORITY),
                RequestBodies.text(body, "content"));
    }
}
