package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.Strings;
import id.jakforge.forgehub.model.FileContent;

/**
 * Unggah berkas ke ember (POST /api/buckets/{name}/files).
 *
 * <p>Nama berkasnya dibersihkan dan diperiksa layanan — pemisah jalur dibuang
 * dulu, baru kemudian diketahui apakah masih ada nama yang tersisa.
 *
 * @param contentBase64 isi berkas sebagai base64
 */
public record UploadFileRequest(String fileName, String contentBase64, String contentType) {

    public UploadFileRequest {
        fileName = Strings.trimToNull(fileName);
        contentBase64 = contentBase64 == null ? "" : contentBase64;
        contentType = Strings.defaultIfEmpty(contentType, FileContent.DEFAULT_CONTENT_TYPE);
    }
}
