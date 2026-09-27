package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.dto.response.AlertSummaryResponse;
import id.jakforge.openorchestrator.dto.response.ChangedCountResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.AlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Peringatan. */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    /** {@code severity} boleh lebih dari satu, sama seperti {@code level} pada catatan. */
    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                             @RequestParam(required = false) String unread,
                                             @RequestParam(required = false) List<String> severity,
                                             @RequestParam(required = false) Integer limit) {
        return alertService.findAll(principal, unread, severity, limit);
    }

    /** Jumlah yang belum dibaca dan delapan yang terbaru, untuk lonceng di bilah atas. */
    @GetMapping("/summary")
    public AlertSummaryResponse getSummary(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return alertService.getSummary(principal);
    }

    @PostMapping("/{id}/read")
    public ChangedCountResponse markRead(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                         @PathVariable long id) {
        return alertService.markRead(principal, id);
    }

    @PostMapping("/read-all")
    public ChangedCountResponse markAllRead(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return alertService.markAllRead(principal);
    }
}
