package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.common.Badan;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.service.FolderService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Folder dan penugasannya.
 *
 * <p>{@code /manage} dan {@code /personal} dipetakan sebagai jalur tetap,
 * bukan id: Spring memilih pola tetap lebih dulu daripada {@code /{id}}, jadi
 * keduanya tidak pernah terbaca sebagai folder bernama "manage".
 */
@RestController
@RequestMapping("/api/folders")
public class FolderController {

    private final FolderService service;

    public FolderController(FolderService service) {
        this.service = service;
    }

    /** Isi bilah folder: folder yang boleh dilihat, dan Folder Saya. */
    @GetMapping
    public Map<String, Object> daftar() {
        return service.daftar(CurrentUser.get());
    }

    /** Semua folder beserta isinya, untuk halaman pengelolaan. Hanya Administrator. */
    @GetMapping("/manage")
    public List<Map<String, Object>> kelola() {
        return service.kelola(CurrentUser.get());
    }

    @PostMapping
    public Map<String, Object> buat(@RequestBody(required = false) Map<String, Object> body) {
        return service.buat(CurrentUser.get(), Permintaan.Folder.dari(body));
    }

    /** Folder Saya, dibuat saat pertama kali dibuka. */
    @PostMapping("/personal")
    public Map<String, Object> pribadi() {
        return service.pribadi(CurrentUser.get());
    }

    @PutMapping("/{id}")
    public Map<String, Object> ubah(@PathVariable String id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        service.ubah(CurrentUser.get(), id, Permintaan.Folder.dari(body));

        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> hapus(@PathVariable String id) {
        service.hapus(CurrentUser.get(), id);

        return Map.of("ok", true);
    }

    // -----------------------------------------------------------------
    // Penugasan
    // -----------------------------------------------------------------

    @GetMapping("/{id}/members")
    public Map<String, Object> anggota(@PathVariable String id) {
        return service.anggota(CurrentUser.get(), id);
    }

    @PostMapping("/{id}/users")
    public Map<String, Object> tugaskanPengguna(@PathVariable String id,
                                                @RequestBody(required = false) Map<String, Object> body) {
        service.tugaskanPengguna(CurrentUser.get(), id, Badan.nama(body, "username"));

        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}/users/{username}")
    public Map<String, Object> lepasPengguna(@PathVariable String id, @PathVariable String username) {
        service.lepasPengguna(CurrentUser.get(), id, username);

        return Map.of("ok", true);
    }

    @PostMapping("/{id}/robots")
    public Map<String, Object> tugaskanRobot(@PathVariable String id,
                                             @RequestBody(required = false) Map<String, Object> body) {
        service.tugaskanRobot(CurrentUser.get(), id, Badan.nama(body, "robotName"));

        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}/robots/{name}")
    public Map<String, Object> lepasRobot(@PathVariable String id, @PathVariable String name) {
        service.lepasRobot(CurrentUser.get(), id, name);

        return Map.of("ok", true);
    }
}
