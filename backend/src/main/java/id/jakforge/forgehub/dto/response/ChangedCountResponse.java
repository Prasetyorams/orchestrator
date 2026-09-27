package id.jakforge.forgehub.dto.response;

/** Berapa baris yang berubah. */
public record ChangedCountResponse(boolean ok, int changed) {
}
