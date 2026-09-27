package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.common.Timestamps;
import id.jakforge.openorchestrator.dto.response.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kesehatan layanan.
 *
 * <p>Satu-satunya endpoint /api yang boleh dicapai tanpa token, selain
 * login. Pemeriksa kesehatan container memanggilnya tiap sepuluh detik, dan
 * pemeriksa yang harus masuk lebih dulu bukan pemeriksa kesehatan.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public HealthResponse getHealth() {
        return HealthResponse.up(Timestamps.nowText());
    }
}
