package id.jakforge.openorchestrator.dto.response;

/** Berapa baris yang terhapus. */
public record DeletedCountResponse(boolean ok, int deleted) {
}
