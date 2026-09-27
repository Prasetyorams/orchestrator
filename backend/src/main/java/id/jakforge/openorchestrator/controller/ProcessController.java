package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.dto.request.MoveToFolderRequest;
import id.jakforge.openorchestrator.dto.request.SaveProcessRequest;
import id.jakforge.openorchestrator.dto.response.OkResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.ProcessService;
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

/** Proses. */
@RestController
@RequestMapping("/api/processes")
@RequiredArgsConstructor
public class ProcessController {

    private final ProcessService processService;

    /** Tanpa {@code folderId}: seluruh penyewa, bentuk yang dibaca Studio dan JakRunner. */
    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                             @RequestParam(required = false) String folderId) {
        return processService.findAll(principal, folderId);
    }

    /** Membuat, atau memperbarui proses bernama itu di folder yang sama. */
    @PostMapping
    public OkResponse save(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                           @Valid @RequestBody SaveProcessRequest request) {
        processService.save(principal, request);

        return OkResponse.success();
    }

    /**
     * {@code ?folderId=} adalah folder ASAL — sama seperti di endpoint lain,
     * folder yang sedang dibuka — dan {@code folderId} di badan adalah folder
     * TUJUAN.
     */
    @PutMapping("/{name}/folder")
    public OkResponse moveToFolder(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String name,
                                   @RequestParam(required = false) String folderId,
                                   @Valid @RequestBody MoveToFolderRequest request) {
        processService.moveToFolder(principal, name, folderId, request);

        return OkResponse.success();
    }

    /** Nama proses unik per folder: {@code ?folderId=} menyebut yang mana. */
    @DeleteMapping("/{name}")
    public OkResponse delete(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String name,
                             @RequestParam(required = false) String folderId) {
        processService.delete(principal, name, folderId);

        return OkResponse.success();
    }
}
