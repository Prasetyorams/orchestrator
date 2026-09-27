package id.jakforge.openorchestrator.dto.response;

/** Jawaban penerbitan paket — dibaca Studio. */
public record PublishPackageResponse(boolean ok, String name, String version, long sizeBytes) {
}
