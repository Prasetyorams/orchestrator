package id.jakforge.forgehub.dto.response;

/** Jawaban GET /api/health. */
public record HealthResponse(String product, String status, String time) {

    static final String PRODUCT = "ForgeHub";
    static final String STATUS_OK = "OK";

    public static HealthResponse up(String time) {
        return new HealthResponse(PRODUCT, STATUS_OK, time);
    }
}
