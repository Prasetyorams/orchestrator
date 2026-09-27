package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.request.LogBatchRequest;
import id.jakforge.forgehub.dto.response.DeletedCountResponse;
import id.jakforge.forgehub.dto.response.LogWriteResponse;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.LogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Catatan jalannya automasi. */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogService logService;

    /** {@code level} boleh lebih dari satu: {@code ?level=WARN,ERROR} atau {@code ?level=WARN&level=ERROR}. */
    @GetMapping
    public List<Map<String, Object>> search(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                            @RequestParam(required = false) List<String> level,
                                            @RequestParam(required = false) String robot,
                                            @RequestParam(required = false) String process,
                                            @RequestParam(required = false) String jobId,
                                            @RequestParam(required = false) String folderId,
                                            @RequestParam(required = false) Integer limit) {
        return logService.search(principal, level, robot, process, jobId, folderId, limit);
    }

    /**
     * Kiriman berkelompok dari robot.
     *
     * <p>Badannya dibaca longgar: bentuk isinya bermacam-macam antar versi
     * robot, dan layanannya yang memutuskan baris mana yang bisa dipakai.
     */
    @PostMapping
    public LogWriteResponse write(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return logService.write(principal, LogBatchRequest.fromBody(body));
    }

    @DeleteMapping
    public DeletedCountResponse purgeOlderThan(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                               @RequestParam(name = "olderThanDays", required = false) Integer days) {
        return logService.purgeOlderThan(principal, days);
    }
}
