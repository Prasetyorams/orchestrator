package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Masuk, siapa saya, ganti kata sandi, dan pemeriksaan kesehatan. */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    /**
     * Kesehatan layanan.
     *
     * <p>Satu-satunya endpoint /api yang boleh dicapai tanpa token, selain
     * login. Pemeriksa kesehatan container memanggilnya tiap sepuluh detik, dan
     * pemeriksa yang harus masuk lebih dulu bukan pemeriksa kesehatan.
     */
    @GetMapping("/health")
    public Map<String, Object> kesehatan() {
        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("product", "ForgeHub");
        hasil.put("status", "OK");
        hasil.put("time", Db.nowText());

        return hasil;
    }

    @PostMapping("/auth/login")
    public Map<String, Object> masuk(@RequestBody(required = false) Map<String, Object> body) {
        return service.masuk(Permintaan.Masuk.dari(body));
    }

    @GetMapping("/auth/me")
    public Map<String, Object> profil() {
        return service.profil(CurrentUser.get());
    }

    @PostMapping("/auth/password")
    public Map<String, Object> gantiSandi(@RequestBody(required = false) Map<String, Object> body) {
        return service.gantiSandi(CurrentUser.get(), Permintaan.GantiSandi.dari(body));
    }
}
