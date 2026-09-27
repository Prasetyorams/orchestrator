package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.dto.request.SaveTriggerRequest;
import id.jakforge.openorchestrator.dto.response.OkResponse;
import id.jakforge.openorchestrator.dto.response.TriggerSaveResponse;
import id.jakforge.openorchestrator.dto.response.TriggerToggleResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.TriggerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

/**
 * Pemicu terjadwal.
 *
 * <p>Pemicu tinggal di folder prosesnya, dan namanya unik per folder:
 * menyimpan membawa {@code folderId} di badan, mengalihkan dan menghapus
 * membawa {@code ?folderId=}.
 */
@RestController
@RequestMapping("/api/triggers")
@RequiredArgsConstructor
public class TriggerController {

    private final TriggerService triggerService;

    /** Tanpa {@code folderId}: pemicu seluruh penyewa. */
    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                             @RequestParam(required = false) String folderId) {
        return triggerService.findAll(principal, folderId);
    }

    @PostMapping
    public TriggerSaveResponse save(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                    @Valid @RequestBody SaveTriggerRequest request) {
        return triggerService.save(principal, request);
    }

    @PostMapping("/{name}/toggle")
    public TriggerToggleResponse toggle(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                        @PathVariable String name,
                                        @RequestParam(required = false) String folderId) {
        return triggerService.toggle(principal, name, folderId);
    }

    @DeleteMapping("/{name}")
    public OkResponse delete(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String name,
                             @RequestParam(required = false) String folderId) {
        triggerService.delete(principal, name, folderId);

        return OkResponse.success();
    }
}
