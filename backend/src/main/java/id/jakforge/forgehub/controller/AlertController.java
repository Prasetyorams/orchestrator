package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.response.AlertSummaryResponse;
import id.jakforge.forgehub.dto.response.ChangedCountResponse;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.AlertService;
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
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                             @RequestParam(required = false) String unread,
                                             @RequestParam(required = false) List<String> severity,
                                             @RequestParam(required = false) Integer limit) {
        return alertService.findAll(principal, unread, severity, limit);
    }

    /** Jumlah yang belum dibaca dan delapan yang terbaru, untuk lonceng di bilah atas. */
    @GetMapping("/summary")
    public AlertSummaryResponse getSummary(@AuthenticationPrincipal ForgeHubPrincipal principal) {
        return alertService.getSummary(principal);
    }

    @PostMapping("/{id}/read")
    public ChangedCountResponse markRead(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                         @PathVariable long id) {
        return alertService.markRead(principal, id);
    }

    @PostMapping("/read-all")
    public ChangedCountResponse markAllRead(@AuthenticationPrincipal ForgeHubPrincipal principal) {
        return alertService.markAllRead(principal);
    }
}
