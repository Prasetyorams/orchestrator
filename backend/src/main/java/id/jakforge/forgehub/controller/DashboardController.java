package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Halaman utama. */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * Angka keempat periode — hari, minggu, bulan, tahun ini — ada di
     * {@code periods}. Tanpa {@code folderId}: seluruh penyewa.
     */
    @GetMapping
    public Map<String, Object> getDashboard(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                            @RequestParam(required = false) String folderId) {
        return dashboardService.getDashboard(principal, folderId);
    }

    /** Tanpa {@code period}: empat belas hari terakhir, bentuk lamanya. */
    @GetMapping("/history")
    public List<Map<String, Object>> getHistory(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                                @RequestParam(required = false) String period) {
        return dashboardService.getHistory(principal, period);
    }
}
