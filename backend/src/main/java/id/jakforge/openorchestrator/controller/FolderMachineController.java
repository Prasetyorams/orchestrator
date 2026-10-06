package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.dto.request.FolderMachinesRequest;
import id.jakforge.openorchestrator.dto.response.OkResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.FolderMachineService;
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
 * Mesin per folder (V12): mesin yang terdaftar di sebuah folder, mendaftarkan
 * dan mengeluarkannya, dan mesin yang bisa dipilih Start Job untuk sebuah
 * proses. Siapa yang boleh apa diputuskan {@link FolderMachineService}.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FolderMachineController {

    private final FolderMachineService folderMachineService;

    @GetMapping("/folders/{id}/machines")
    public List<Map<String, Object>> list(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                          @PathVariable String id) {
        return folderMachineService.list(principal, id);
    }

    @GetMapping("/folders/{id}/available-machines")
    public List<Map<String, Object>> available(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                               @PathVariable String id) {
        return folderMachineService.available(principal, id);
    }

    @PostMapping("/folders/{id}/machines")
    public OkResponse add(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String id,
                          @RequestBody FolderMachinesRequest request) {
        folderMachineService.add(principal, id, request.machineId());

        return OkResponse.success();
    }

    @PostMapping("/folders/{id}/machines/bulk")
    public FolderMachineService.BulkResult addAll(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                                  @PathVariable String id, @RequestBody FolderMachinesRequest request) {
        return folderMachineService.addAll(principal, id, request.machineIds());
    }

    @DeleteMapping("/folders/{id}/machines/{machineId}")
    public OkResponse remove(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String id,
                             @PathVariable String machineId) {
        folderMachineService.remove(principal, id, machineId);

        return OkResponse.success();
    }

    /** Mesin folder proses itu, untuk pilihan Mesin di Start Job. */
    @GetMapping("/processes/{id}/available-machines")
    public FolderMachineService.ProcessMachines forProcess(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                                           @PathVariable String id,
                                                           @RequestParam(required = false) String runtimeType) {
        return folderMachineService.forProcess(principal, id, runtimeType);
    }
}
