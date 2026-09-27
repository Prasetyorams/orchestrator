package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.dto.request.MoveToFolderRequest;
import id.jakforge.openorchestrator.dto.request.SaveAssetRequest;
import id.jakforge.openorchestrator.dto.response.OkResponse;
import id.jakforge.openorchestrator.dto.response.SaveResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.AssetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Aset, termasuk kredensial (aset bertipe Credential). */
@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    /** Tanpa {@code folderId}: aset seluruh penyewa. */
    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                             @RequestParam(required = false) String folderId) {
        return assetService.findAll(principal, folderId);
    }

    @PostMapping
    public SaveResponse save(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                             @Valid @RequestBody SaveAssetRequest request) {
        return assetService.save(principal, request);
    }

    @PutMapping("/{name}/folder")
    public OkResponse moveToFolder(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String name,
                                   @Valid @RequestBody MoveToFolderRequest request) {
        assetService.moveToFolder(principal, name, request);

        return OkResponse.success();
    }

    /** Dipanggil activity Get Asset; rahasia dibuka di sini saja. */
    @GetMapping("/{name}/value")
    public Map<String, Object> getValue(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                        @PathVariable String name) {
        return assetService.getValue(principal, name);
    }

    @DeleteMapping("/{name}")
    public OkResponse delete(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String name) {
        assetService.delete(principal, name);

        return OkResponse.success();
    }
}
