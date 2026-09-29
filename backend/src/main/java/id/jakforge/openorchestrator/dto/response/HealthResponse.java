package id.jakforge.openorchestrator.dto.response;

import java.util.List;

/**
 * Jawaban GET /api/health.
 *
 * <p>{@code contract} dan {@code capabilities} dibaca robot v1 sebelum memakai
 * kemampuan tambahan; {@code apiVersions} dibaca Robot Agent untuk memilih
 * antara v1 (akun pengguna) dan v2 (/api/agent, machine key). Robot lama
 * mengabaikan ketiganya.
 */
public record HealthResponse(String product, String status, String time, int contract, List<String> capabilities,
                             List<Integer> apiVersions) {

    static final String PRODUCT = "OpenOrchestrator";
    static final String STATUS_OK = "OK";

    /** Tingkat kontrak robot v1 yang dikenali klien robot (lihat ROBOT-API.md 1). */
    static final int CONTRACT = 2;

    static final List<String> CAPABILITIES = List.of(
            "jobs.next.package",   // jobs/next menyebut paket, versi, titik masuk, SHA-256, folder
            "packages.sha256",     // SHA-256 isi paket di daftar paket dan di jobs/next
            "heartbeat.commands",  // jawaban denyut membawa StopJob
            "jobs.state.guard");   // POST state menolak (409) RUNNING sesudah STOPPING dan menghidupkan job selesai

    static final List<Integer> API_VERSIONS = List.of(1, 2);

    public static HealthResponse up(String time) {
        return new HealthResponse(PRODUCT, STATUS_OK, time, CONTRACT, CAPABILITIES, API_VERSIONS);
    }
}
