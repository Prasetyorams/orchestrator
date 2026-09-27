package id.jakforge.openorchestrator.dto.response;

/** {@code {"ok": true, "created": ...}} — simpan yang bisa membuat ATAU memperbarui. */
public record SaveResponse(boolean ok, boolean created) {

    public static SaveResponse of(boolean created) {
        return new SaveResponse(true, created);
    }
}
