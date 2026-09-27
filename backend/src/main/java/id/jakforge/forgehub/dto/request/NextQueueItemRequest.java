package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.RequestBodies;

import java.util.Map;

/** Robot mengambil butir berikutnya (POST /api/queues/{name}/next), dibaca longgar. */
public record NextQueueItemRequest(String robotName) {

    public static NextQueueItemRequest fromBody(Map<String, Object> body) {
        return new NextQueueItemRequest(RequestBodies.text(body, "robotName"));
    }
}
