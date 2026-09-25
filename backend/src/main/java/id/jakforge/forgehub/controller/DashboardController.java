package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Halaman utama dan pencarian menyeluruh. */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    /** Angka keempat periode — hari, minggu, bulan, tahun ini — ada di {@code periods}. */
    @GetMapping("/dashboard")
    public Map<String, Object> dasbor() {
        return service.dasbor(CurrentUser.get().tenantId());
    }

    /** Tanpa {@code period}: empat belas hari terakhir, bentuk lamanya. */
    @GetMapping("/dashboard/history")
    public List<Map<String, Object>> riwayat(@RequestParam(required = false) String period) {
        return service.riwayat(CurrentUser.get().tenantId(), period);
    }

    @GetMapping("/search")
    public List<Map<String, Object>> cari(@RequestParam(name = "q", required = false) String q) {
        return service.cari(CurrentUser.get().tenantId(), q);
    }
}
