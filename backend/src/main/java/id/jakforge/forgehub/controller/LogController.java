package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.service.LogService;
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

/** Catatan dan peringatan. */
@RestController
@RequestMapping("/api")
public class LogController {

    private final LogService service;

    public LogController(LogService service) {
        this.service = service;
    }

    private static UUID tenant() {
        return CurrentUser.get().tenantId();
    }

    // -----------------------------------------------------------------
    // Catatan
    // -----------------------------------------------------------------

    @GetMapping("/logs")
    public List<Map<String, Object>> daftar(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String robot,
            @RequestParam(required = false) String process,
            @RequestParam(name = "jobId", required = false) String jobId,
            @RequestParam(required = false) Integer limit) {

        return service.cari(tenant(), level, robot, process, jobId, limit);
    }

    /**
     * Kiriman berkelompok dari robot.
     *
     * <p>Badan permintaannya dibaca sebagai Map dan medan {@code lines}
     * diserahkan apa adanya ke service. Bentuk isinya bermacam-macam antar
     * versi robot, dan service yang memutuskan mana yang bisa dipakai.
     */
    @PostMapping("/logs")
    public Map<String, Object> tulis(@RequestBody(required = false) Map<String, Object> body) {
        return service.tulis(tenant(), body == null ? null : body.get("lines"));
    }

    @DeleteMapping("/logs")
    public Map<String, Object> bersihkan(
            @RequestParam(name = "olderThanDays", required = false) Integer hari) {

        return service.bersihkan(tenant(), hari);
    }

    // -----------------------------------------------------------------
    // Peringatan
    // -----------------------------------------------------------------

    @GetMapping("/alerts")
    public List<Map<String, Object>> peringatan(
            @RequestParam(required = false) String unread,
            @RequestParam(required = false) Integer limit) {

        return service.peringatan(tenant(), unread, limit);
    }

    @PostMapping("/alerts/{id}/read")
    public Map<String, Object> tandaiDibaca(@PathVariable long id) {
        return service.tandaiDibaca(tenant(), id);
    }

    @PostMapping("/alerts/read-all")
    public Map<String, Object> tandaiSemua() {
        return service.tandaiSemuaDibaca(tenant());
    }
}
