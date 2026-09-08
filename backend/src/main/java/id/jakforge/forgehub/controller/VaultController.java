package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.service.VaultService;
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

/** Aset, kredensial, dan gudang berkas. */
@RestController
@RequestMapping("/api")
public class VaultController {

    private final VaultService service;

    public VaultController(VaultService service) {
        this.service = service;
    }

    private static UUID tenant() {
        return CurrentUser.get().tenantId();
    }

    // -----------------------------------------------------------------
    // Aset
    // -----------------------------------------------------------------

    @GetMapping("/assets")
    public List<Map<String, Object>> aset() {
        return service.aset(tenant());
    }

    @PostMapping("/assets")
    public Map<String, Object> simpanAset(@RequestBody(required = false) Map<String, Object> body) {
        return service.simpanAset(tenant(), Permintaan.Aset.dari(body));
    }

    @GetMapping("/assets/{name}/value")
    public Map<String, Object> nilaiAset(@PathVariable String name) {
        return service.nilaiAset(tenant(), name);
    }

    @DeleteMapping("/assets/{name}")
    public Map<String, Object> hapusAset(@PathVariable String name) {
        service.hapusAset(tenant(), name);

        return Map.of("ok", true);
    }

    // -----------------------------------------------------------------
    // Kredensial
    // -----------------------------------------------------------------

    @GetMapping("/credentials")
    public List<Map<String, Object>> kredensial() {
        return service.kredensial(tenant());
    }

    @PostMapping("/credentials")
    public Map<String, Object> simpanKredensial(@RequestBody(required = false) Map<String, Object> body) {
        service.simpanKredensial(tenant(), Permintaan.Kredensial.dari(body));

        return Map.of("ok", true);
    }

    @GetMapping("/credentials/{name}/value")
    public Map<String, Object> nilaiKredensial(@PathVariable String name) {
        return service.nilaiKredensial(tenant(), name);
    }

    @DeleteMapping("/credentials/{name}")
    public Map<String, Object> hapusKredensial(@PathVariable String name) {
        service.hapusKredensial(tenant(), name);

        return Map.of("ok", true);
    }

    // -----------------------------------------------------------------
    // Gudang berkas
    // -----------------------------------------------------------------

    @GetMapping("/buckets")
    public List<Map<String, Object>> gudang() {
        return service.daftarGudang(tenant());
    }

    @PostMapping("/buckets")
    public Map<String, Object> buatGudang(@RequestBody(required = false) Map<String, Object> body) {
        service.buatGudang(tenant(), Permintaan.Bernama.dari(body));

        return Map.of("ok", true);
    }

    @DeleteMapping("/buckets/{name}")
    public Map<String, Object> hapusGudang(@PathVariable String name) {
        service.hapusGudang(tenant(), name);

        return Map.of("ok", true);
    }

    @GetMapping("/buckets/{name}/files")
    public List<Map<String, Object>> berkas(@PathVariable String name) {
        return service.berkas(tenant(), name);
    }

    @PostMapping("/buckets/{name}/files")
    public Map<String, Object> unggah(@PathVariable String name,
                                      @RequestBody(required = false) Map<String, Object> body) {

        return service.unggah(tenant(), name, CurrentUser.get().username(),
                Permintaan.Berkas.dari(body));
    }

    @GetMapping("/buckets/{name}/files/{id}/content")
    public ResponseEntity<byte[]> isiBerkas(@PathVariable String name, @PathVariable String id) {
        Map<String, Object> berkas = service.isiBerkas(tenant(), name, id);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(String.valueOf(berkas.get("contentType"))))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(String.valueOf(berkas.get("fileName")))
                                .build().toString())
                .body((byte[]) berkas.get("content"));
    }

    @DeleteMapping("/buckets/{name}/files/{id}")
    public Map<String, Object> hapusBerkas(@PathVariable String name, @PathVariable String id) {
        service.hapusBerkas(tenant(), name, id);

        return Map.of("ok", true);
    }
}
