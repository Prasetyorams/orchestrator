package id.jakforge.openorchestrator.dto.response;

/** Hasil kiriman catatan: berapa baris ditulis, berapa dilewati karena tingkat rincian. */
public record LogWriteResponse(boolean ok, int written, int skipped) {
}
