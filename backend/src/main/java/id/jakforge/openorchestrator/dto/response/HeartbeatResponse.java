package id.jakforge.openorchestrator.dto.response;

/** Jawaban denyut robot: waktu server, supaya robot bisa melihat selisih jamnya. */
public record HeartbeatResponse(boolean ok, String serverTime) {

    public static HeartbeatResponse at(String serverTime) {
        return new HeartbeatResponse(true, serverTime);
    }
}
