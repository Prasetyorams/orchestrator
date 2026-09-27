package id.jakforge.openorchestrator.dto.response;

/** {@code {"status": "OK"}} — bentuk jawaban ganti kata sandi, sama dengan OpenOrchestrator .NET. */
public record StatusResponse(String status) {

    private static final StatusResponse OK = new StatusResponse("OK");

    public static StatusResponse ok() {
        return OK;
    }
}
