package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.model.FileContent;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Balasan unduhan: bita mentah dengan nama berkas di Content-Disposition.
 *
 * <p>Satu-satunya jenis balasan yang bukan JSON — karena itu memakai
 * ResponseEntity, sedangkan endpoint lain cukup mengembalikan objeknya.
 */
final class DownloadResponses {

    private DownloadResponses() {
    }

    static ResponseEntity<byte[]> attachment(FileContent file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.content());
    }
}
