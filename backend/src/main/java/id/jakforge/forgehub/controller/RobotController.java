package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.service.RobotService;
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

/** Robot, mesin, dan lingkungan. */
@RestController
@RequestMapping("/api")
public class RobotController {

    private final RobotService service;

    public RobotController(RobotService service) {
        this.service = service;
    }

    private static UUID tenant() {
        return CurrentUser.get().tenantId();
    }

    // -----------------------------------------------------------------
    // Robot
    // -----------------------------------------------------------------

    @GetMapping("/robots")
    public List<Map<String, Object>> robots() {
        return service.daftar(tenant());
    }

    @GetMapping("/robots/{name}")
    public Map<String, Object> robot(@PathVariable String name) {
        return service.satu(tenant(), name);
    }

    @PostMapping("/robots/{name}/heartbeat")
    public Map<String, Object> denyut(@PathVariable String name,
                                      @RequestBody(required = false) Map<String, Object> body) {

        return service.denyut(tenant(), name, Permintaan.Denyut.dari(body));
    }

    @PostMapping("/robots")
    public Map<String, Object> buatRobot(@RequestBody(required = false) Map<String, Object> body) {
        service.buat(tenant(), Permintaan.Robot.dari(body));

        return Map.of("ok", true);
    }

    @DeleteMapping("/robots/{name}")
    public Map<String, Object> hapusRobot(@PathVariable String name) {
        service.hapus(tenant(), name);

        return Map.of("ok", true);
    }

    // -----------------------------------------------------------------
    // Mesin
    // -----------------------------------------------------------------

    @GetMapping("/machines")
    public List<Map<String, Object>> mesin() {
        return service.mesin(tenant());
    }

    @PostMapping("/machines")
    public Map<String, Object> buatMesin(@RequestBody(required = false) Map<String, Object> body) {
        service.buatMesin(tenant(), Permintaan.Mesin.dari(body));

        return Map.of("ok", true);
    }

    @DeleteMapping("/machines/{name}")
    public Map<String, Object> hapusMesin(@PathVariable String name) {
        service.hapusMesin(tenant(), name);

        return Map.of("ok", true);
    }

    // -----------------------------------------------------------------
    // Lingkungan
    // -----------------------------------------------------------------

    @GetMapping("/environments")
    public List<Map<String, Object>> lingkungan() {
        return service.lingkungan(tenant());
    }

    @PostMapping("/environments")
    public Map<String, Object> buatLingkungan(@RequestBody(required = false) Map<String, Object> body) {
        service.buatLingkungan(tenant(), Permintaan.Bernama.dari(body));

        return Map.of("ok", true);
    }

    @DeleteMapping("/environments/{name}")
    public Map<String, Object> hapusLingkungan(@PathVariable String name) {
        service.hapusLingkungan(tenant(), name);

        return Map.of("ok", true);
    }
}
