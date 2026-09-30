package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.dto.request.LogBatchRequest;
import id.jakforge.openorchestrator.dto.request.LogSearchRequest;
import id.jakforge.openorchestrator.dto.response.DeletedCountResponse;
import id.jakforge.openorchestrator.dto.response.LogWriteResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.LogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Catatan jalannya automasi.
 *
 * <p>Daftar, pilihan saringan, dan ekspor membaca saringan yang SAMA
 * ({@link LogSearchRequest}): yang diekspor dan yang ditawarkan sebagai
 * pilihan selalu sesuai dengan yang tampil di tabel.
 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogService logService;

    /**
     * {@code level} boleh lebih dari satu: {@code ?level=WARN,ERROR} atau {@code ?level=WARN&level=ERROR}.
     * Saringan lain: robot, process, jobId, folderId, machine, host, time/from/to, q, limit.
     */
    @GetMapping
    public List<Map<String, Object>> search(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                            @RequestParam MultiValueMap<String, String> params) {
        return logService.search(principal, LogSearchRequest.fromParams(params));
    }

    /** Pilihan saringan Mesin, Proses, dan Host Identity untuk folder dan rentang waktu itu. */
    @GetMapping("/filters")
    public Map<String, List<String>> filterOptions(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                                   @RequestParam MultiValueMap<String, String> params) {
        return logService.filterOptions(principal, LogSearchRequest.fromParams(params));
    }

    /** CSV dengan saringan yang sama dengan daftar. */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                         @RequestParam MultiValueMap<String, String> params) {
        return DownloadResponses.attachment(logService.export(principal, LogSearchRequest.fromParams(params)));
    }

    /**
     * Kiriman berkelompok dari robot.
     *
     * <p>Badannya dibaca longgar: bentuk isinya bermacam-macam antar versi
     * robot, dan layanannya yang memutuskan baris mana yang bisa dipakai.
     */
    @PostMapping
    public LogWriteResponse write(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return logService.write(principal, LogBatchRequest.fromBody(body));
    }

    @DeleteMapping
    public DeletedCountResponse purgeOlderThan(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                               @RequestParam(name = "olderThanDays", required = false) Integer days) {
        return logService.purgeOlderThan(principal, days);
    }
}
