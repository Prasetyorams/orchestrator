package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.common.Badan;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.security.Izin;
import id.jakforge.forgehub.service.CatalogService;
import id.jakforge.forgehub.service.FolderService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Proses dan paket. */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService service;
    private final FolderService folders;
    private final Izin izin;

    public CatalogController(CatalogService service, FolderService folders, Izin izin) {
        this.service = service;
        this.folders = folders;
        this.izin = izin;
    }

    /** Folder dari parameter atau badan permintaan, sesudah diperiksa haknya; null kalau tidak disebut. */
    private UUID folder(String folderId) {
        return folders.saring(CurrentUser.get(), folderId);
    }

    private static UUID tenant() {
        return CurrentUser.get().tenantId();
    }

    // -----------------------------------------------------------------
    // Proses
    // -----------------------------------------------------------------

    /** Tanpa {@code folderId}: seluruh penyewa, bentuk yang dibaca Studio dan JakRunner. */
    @GetMapping("/processes")
    public List<Map<String, Object>> proses(@RequestParam(required = false) String folderId) {
        return service.proses(tenant(), folder(folderId));
    }

    @PostMapping("/processes")
    public Map<String, Object> simpanProses(@RequestBody(required = false) Map<String, Object> body) {
        service.simpanProses(tenant(), Permintaan.Proses.dari(body), folder(Badan.teks(body, "folderId")),
                izin.penjaga(CurrentUser.get()));

        return Map.of("ok", true);
    }

    /**
     * {@code ?folderId=} adalah folder ASAL — sama seperti di endpoint lain,
     * folder yang sedang dibuka — dan {@code folderId} di badan adalah folder
     * TUJUAN. Tanpa folder asal, yang dipindah adalah proses bernama itu di
     * mana pun ia berada (lihat CatalogService.pilihFolder).
     */
    @PutMapping("/processes/{name}/folder")
    public Map<String, Object> pindahProses(@PathVariable String name,
                                            @RequestParam(required = false) String folderId,
                                            @RequestBody(required = false) Map<String, Object> body) {
        service.pindahProses(tenant(), name, folder(folderId), folder(Badan.teks(body, "folderId")));

        return Map.of("ok", true);
    }

    /** Nama proses unik per folder: {@code ?folderId=} menyebut yang mana. */
    @DeleteMapping("/processes/{name}")
    public Map<String, Object> hapusProses(@PathVariable String name,
                                           @RequestParam(required = false) String folderId) {
        service.hapusProses(tenant(), name, folder(folderId));

        return Map.of("ok", true);
    }

    // -----------------------------------------------------------------
    // Paket
    // -----------------------------------------------------------------

    @GetMapping("/packages")
    public List<Map<String, Object>> paket(@RequestParam(required = false) String folderId) {
        return service.paket(tenant(), folder(folderId));
    }

    @PostMapping("/packages")
    public Map<String, Object> terbitkan(@RequestBody(required = false) Map<String, Object> body) {
        return service.terbitkan(tenant(), CurrentUser.get().username(), Permintaan.Paket.dari(body),
                izin.penjaga(CurrentUser.get()));
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
