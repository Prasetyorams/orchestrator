package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.RequestBodies;

import java.util.HashMap;
import java.util.Map;

/**
 * Penerbitan paket dari Studio (POST /api/packages), dibaca longgar.
 *
 * @param version       versi baru; yang sudah pernah terbit ditolak 409 (lihat PackageService#publish)
 * @param description   keterangan versi ini — catatan rilis dari dialog Publish Studio
 * @param contentBase64 isi paket (.zip) sebagai base64
 */
public record PublishPackageRequest(String name, String version, String description, String entryPoint,
                                    String environment, String contentBase64) {

    static final String DEFAULT_VERSION = "1.0.0";
    public static final String DEFAULT_ENVIRONMENT = "Production";

    public static PublishPackageRequest fromBody(Map<String, Object> body) {
        return new PublishPackageRequest(
                RequestBodies.trimmedText(body, "name"),
                RequestBodies.text(body, "version", DEFAULT_VERSION),
                RequestBodies.text(body, "description"),
                RequestBodies.text(body, "entryPoint"),
                RequestBodies.text(body, "environment", DEFAULT_ENVIRONMENT),
                RequestBodies.text(body, "contentBase64"));
    }

    /**
     * Medan multipart/form-data (packages.upload.binary): nama medan sama
     * dengan badan JSON, isinya berkas terpisah (bagian {@code file}).
     */
    public static PublishPackageRequest fromForm(Map<String, String> fields) {
        Map<String, Object> body = new HashMap<>(fields == null ? Map.of() : fields);
        // contentBase64 tidak berlaku di multipart; isinya hanya dari bagian berkas.
        body.remove("contentBase64");
        return fromBody(body);
    }
}
