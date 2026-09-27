package id.jakforge.openorchestrator.dto.response;

/** Jawaban unggah berkas: nama yang benar-benar dipakai (sesudah dibersihkan) dan ukurannya. */
public record UploadFileResponse(boolean ok, String fileName, int sizeBytes) {
}
