package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.service.QueueService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public QueueController(QueueService service) {
        this.service = service;
    }

    private static UUID tenant() {
        return CurrentUser.get().tenantId();
    }

    @GetMapping
    public List<Map<String, Object>> daftar() {
        return service.daftar(tenant());
    }

    @PostMapping
    public Map<String, Object> buat(@RequestBody(required = false) Map<String, Object> body) {
        service.buat(tenant(), Permintaan.Antrean.dari(body));

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

        return service.ambilButir(tenant(), name,
                id.jakforge.forgehub.common.Badan.teks(body, "robotName"));
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
