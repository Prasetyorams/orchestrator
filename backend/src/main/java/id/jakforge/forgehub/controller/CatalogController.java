package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.service.CatalogService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Proses dan paket. */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    private static UUID tenant() {
        return CurrentUser.get().tenantId();
    }

    // -----------------------------------------------------------------
    // Proses
    // -----------------------------------------------------------------

    @GetMapping("/processes")
    public List<Map<String, Object>> proses() {
        return service.proses(tenant());
    }

    @PostMapping("/processes")
    public Map<String, Object> simpanProses(@RequestBody(required = false) Map<String, Object> body) {
        service.simpanProses(tenant(), Permintaan.Proses.dari(body));

        return Map.of("ok", true);
    }

    @DeleteMapping("/processes/{name}")
    public Map<String, Object> hapusProses(@PathVariable String name) {
        service.hapusProses(tenant(), name);

        return Map.of("ok", true);
    }

    // -----------------------------------------------------------------
    // Paket
    // -----------------------------------------------------------------

    @GetMapping("/packages")
    public List<Map<String, Object>> paket() {
        return service.paket(tenant());
    }

    @PostMapping("/packages")
    public Map<String, Object> terbitkan(@RequestBody(required = false) Map<String, Object> body) {
        return service.terbitkan(tenant(), CurrentUser.get().username(), Permintaan.Paket.dari(body));
    }

    /**
     * Unduh isi paket.
     *
     * <p>Satu-satunya endpoint yang mengembalikan bita, bukan JSON — karena itu
     * ia memakai ResponseEntity, sedangkan yang lain cukup mengembalikan Map.
     */
    @GetMapping("/packages/{name}/{version}/content")
    public ResponseEntity<byte[]> isiPaket(@PathVariable String name, @PathVariable String version) {
        byte[] isi = service.isiPaket(tenant(), name, version);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(name + "." + version + ".zip")
                                .build().toString())
                .body(isi);
    }

    @DeleteMapping("/packages/{name}/{version}")
    public Map<String, Object> hapusPaket(@PathVariable String name, @PathVariable String version) {
        service.hapusPaket(tenant(), name, version);

        return Map.of("ok", true);
    }
}
