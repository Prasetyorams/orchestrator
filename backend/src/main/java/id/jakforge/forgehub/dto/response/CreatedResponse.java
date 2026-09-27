package id.jakforge.forgehub.dto.response;

import java.util.UUID;

/** {@code {"ok": true, "id": "..."}} — sesuatu yang baru dibuat, beserta id-nya. */
public record CreatedResponse(boolean ok, String id) {

    public static CreatedResponse of(UUID id) {
        return new CreatedResponse(true, id.toString());
    }
}
