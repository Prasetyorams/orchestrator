package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.common.Badan;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.service.FolderService;
import id.jakforge.forgehub.service.QueueService;
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

/** Antrean transaksi. */
@RestController
@RequestMapping("/api/queues")
public class QueueController {

    private final QueueService service;
    private final FolderService folders;

    public QueueController(QueueService service, FolderService folders) {
        this.service = service;
        this.folders = folders;
    }

    private static UUID tenant() {
        return CurrentUser.get().tenantId();
    }

    private UUID folder(String folderId) {
        return folders.saring(CurrentUser.get(), folderId);
    }

    /** Tanpa {@code folderId}: antrean seluruh penyewa. */
    @GetMapping
    public List<Map<String, Object>> daftar(@RequestParam(required = false) String folderId) {
        return service.daftar(tenant(), folder(folderId));
    }

    @PostMapping
    public Map<String, Object> buat(@RequestBody(required = false) Map<String, Object> body) {
        service.buat(tenant(), Permintaan.Antrean.dari(body), folder(Badan.teks(body, "folderId")));

        return Map.of("ok", true);
    }

    @PutMapping("/{name}/folder")
    public Map<String, Object> pindah(@PathVariable String name,
                                      @RequestBody(required = false) Map<String, Object> body) {
        service.pindah(tenant(), name, folder(Badan.teks(body, "folderId")));

        return Map.of("ok", true);
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> hapus(@PathVariable String name) {
        service.hapus(tenant(), name);

        return Map.of("ok", true);
    }

    /**
     * Butir sebuah antrean.
     *
     * <p>Dipetakan sebelum {@code /items/...} supaya niat urutannya terbaca,
     * meski Spring memang memilih pola yang lebih spesifik lebih dulu.
     */
    @GetMapping("/{name}/items")
    public List<Map<String, Object>> butir(@PathVariable String name,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) Integer limit) {

        return service.butir(tenant(), name, status, limit);
    }

    @PostMapping("/{name}/items")
    public Map<String, Object> tambahButir(@PathVariable String name,
                                           @RequestBody(required = false) Map<String, Object> body) {

        return service.tambahButir(tenant(), name, Permintaan.ButirAntrean.dari(body));
    }

    @PostMapping("/{name}/next")
    public Map<String, Object> ambilButir(@PathVariable String name,
                                          @RequestBody(required = false) Map<String, Object> body) {

        return service.ambilButir(tenant(), name, Badan.teks(body, "robotName"));
    }

    @PostMapping("/items/{id}/result")
    public Map<String, Object> hasilButir(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> body) {

        return service.hasilButir(tenant(), id, Permintaan.HasilButir.dari(body));
    }

    @DeleteMapping("/items/{id}")
    public Map<String, Object> hapusButir(@PathVariable String id) {
        service.hapusButir(tenant(), id);

        return Map.of("ok", true);
    }
}
