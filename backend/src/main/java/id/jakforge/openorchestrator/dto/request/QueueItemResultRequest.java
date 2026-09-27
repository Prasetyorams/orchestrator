package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.RequestBodies;

import java.util.Map;

/** Hasil pemrosesan satu butir dari robot (POST /api/queues/items/{id}/result), dibaca longgar. */
public record QueueItemResultRequest(String status, String output, String exception) {

    public static QueueItemResultRequest fromBody(Map<String, Object> body) {
        return new QueueItemResultRequest(
                RequestBodies.text(body, "status", ""),
                RequestBodies.text(body, "output"),
                RequestBodies.text(body, "exception"));
    }
}
