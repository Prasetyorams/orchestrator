package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.service.AdminService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Pengguna, peran, penyewa, lisensi, dan setelan. */
@RestController
@RequestMapping("/api")
public class AdminController {

    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/tenants")
    public List<Map<String, Object>> penyewa() {
        return service.penyewa();
    }

    @GetMapping("/users")
    public List<Map<String, Object>> pengguna() {
        return service.daftarPengguna(CurrentUser.get().tenantId());
    }

    @PostMapping("/users")
    public Map<String, Object> simpanPengguna(@RequestBody(required = false) Map<String, Object> body) {
        return service.simpanPengguna(CurrentUser.get(), Permintaan.Pengguna.dari(body));
    }

    @DeleteMapping("/users/{username}")
    public Map<String, Object> hapusPengguna(@PathVariable String username) {
        service.hapusPengguna(CurrentUser.get(), username);

        return Map.of("ok", true);
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> peran() {
        return service.peran(CurrentUser.get().tenantId());
    }

    @GetMapping("/licensing")
    public List<Map<String, Object>> lisensi() {
        return service.lisensi(CurrentUser.get().tenantId());
    }

    @GetMapping("/settings")
    public Map<String, Object> setelan() {
        return service.setelan(CurrentUser.get());
    }
}
