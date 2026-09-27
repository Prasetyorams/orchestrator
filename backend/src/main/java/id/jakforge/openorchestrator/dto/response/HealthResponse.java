package id.jakforge.openorchestrator.dto.response;

/** Jawaban GET /api/health. */
public record HealthResponse(String product, String status, String time) {

    static final String PRODUCT = "OpenOrchestrator";
    static final String STATUS_OK = "OK";

    public static HealthResponse up(String time) {
        return new HealthResponse(PRODUCT, STATUS_OK, time);
    }
}
