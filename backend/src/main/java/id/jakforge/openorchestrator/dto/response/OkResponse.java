package id.jakforge.openorchestrator.dto.response;

/** {@code {"ok": true}} — jawaban untuk perintah yang tidak mengembalikan apa pun lagi. */
public record OkResponse(boolean ok) {

    private static final OkResponse SUCCESS = new OkResponse(true);

    public static OkResponse success() {
        return SUCCESS;
    }
}
