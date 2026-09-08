package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.TriggerRequest;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.service.TriggerService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Pemicu terjadwal. */
@RestController
@RequestMapping("/api/triggers")
public class TriggerController {

    private final TriggerService service;

    public TriggerController(TriggerService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> daftar() {
        return service.daftar(CurrentUser.get().tenantId());
    }

    @PostMapping
    public Map<String, Object> simpan(@RequestBody(required = false) Map<String, Object> body) {
        return service.simpan(CurrentUser.get().tenantId(), TriggerRequest.dari(body));
    }

    @PostMapping("/{name}/toggle")
    public Map<String, Object> alihkan(@PathVariable String name) {
        return service.alihkan(CurrentUser.get().tenantId(), name);
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> hapus(@PathVariable String name) {
        service.hapus(CurrentUser.get().tenantId(), name);

        return Map.of("ok", true);
    }
}
