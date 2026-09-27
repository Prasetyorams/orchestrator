package id.jakforge.forgehub.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Hasil laporan satu butir antrean.
 *
 * @param attempt percobaan keberapa — hanya ada kalau butirnya dicoba lagi
 */
public record QueueItemResultResponse(
        boolean ok,
        boolean retried,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long attempt) {

    public static QueueItemResultResponse retried(long attempt) {
        return new QueueItemResultResponse(true, true, attempt);
    }

    public static QueueItemResultResponse completed() {
        return new QueueItemResultResponse(true, false, null);
    }
}
