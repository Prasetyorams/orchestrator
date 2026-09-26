package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.Izin;
import id.jakforge.forgehub.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Masuk, siapa saya, ubah profil, ganti kata sandi, dan pemeriksaan kesehatan. */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService service;
    private final Izin izin;

    public AuthController(AuthService service, Izin izin) {
        this.service = service;
        this.izin = izin;
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

    /**
     * Siapa saya, beserta pola izin peran saya — dasbor memakainya untuk tidak
     * menawarkan menu dan tombol yang pasti ditolak. Yang menjaga tetap server.
     */
    @GetMapping("/auth/me")
    public Map<String, Object> profil() {
        return denganIzin(CurrentUser.get(), service.profil(CurrentUser.get()));
    }

    /** Nama tampilan dan surel milik pengguna yang sedang masuk. */
    @PutMapping("/auth/me")
    public Map<String, Object> ubahProfil(@RequestBody(required = false) Map<String, Object> body) {
        return denganIzin(CurrentUser.get(), service.ubahProfil(CurrentUser.get(), Permintaan.Profil.dari(body)));
    }

    private Map<String, Object> denganIzin(ForgeHubPrincipal p, Map<String, Object> profil) {
        Map<String, Object> hasil = new LinkedHashMap<>(profil);
        hasil.put("permissions", new ArrayList<>(izin.pola(p)));

        return hasil;
    }

    @PostMapping("/auth/password")
    public Map<String, Object> gantiSandi(@RequestBody(required = false) Map<String, Object> body) {
        return service.gantiSandi(CurrentUser.get(), Permintaan.GantiSandi.dari(body));
    }
}
