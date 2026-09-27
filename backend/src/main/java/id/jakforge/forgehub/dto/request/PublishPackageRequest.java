package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.RequestBodies;

import java.util.Map;

/**
 * Penerbitan paket dari Studio (POST /api/packages), dibaca longgar.
 *
 * @param contentBase64 isi paket (.zip) sebagai base64; boleh kosong untuk
 *                      penerbitan ulang yang hanya memperbarui keterangan
 */
public record PublishPackageRequest(String name, String version, String description, String entryPoint,
                                    String environment, String contentBase64) {

    static final String DEFAULT_VERSION = "1.0.0";
    static final String DEFAULT_ENVIRONMENT = "Production";

    public static PublishPackageRequest fromBody(Map<String, Object> body) {
        return new PublishPackageRequest(
                RequestBodies.trimmedText(body, "name"),
                RequestBodies.text(body, "version", DEFAULT_VERSION),
                RequestBodies.text(body, "description"),
                RequestBodies.text(body, "entryPoint"),
                RequestBodies.text(body, "environment", DEFAULT_ENVIRONMENT),
                RequestBodies.text(body, "contentBase64"));
    }
}
