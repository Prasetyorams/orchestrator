package id.jakforge.forgehub.dto.response;

/** Berapa baris yang terhapus. */
public record DeletedCountResponse(boolean ok, int deleted) {
}
