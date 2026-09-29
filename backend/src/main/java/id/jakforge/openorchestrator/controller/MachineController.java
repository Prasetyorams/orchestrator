package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.dto.request.CreateMachineRequest;
import id.jakforge.openorchestrator.dto.request.UpdateMachineRequest;
import id.jakforge.openorchestrator.dto.response.MachineKeyResponse;
import id.jakforge.openorchestrator.dto.response.OkResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.MachineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Mesin tempat robot berjalan, dan machine key Robot Agent-nya. */
@RestController
@RequestMapping("/api/machines")
@RequiredArgsConstructor
public class MachineController {

    private final MachineService machineService;

    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return machineService.findAll(principal);
    }

    @PostMapping
    public OkResponse create(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                             @Valid @RequestBody CreateMachineRequest request) {
        machineService.create(principal, request);

        return OkResponse.success();
    }

    @PutMapping("/{name}")
    public OkResponse update(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String name,
                             @Valid @RequestBody UpdateMachineRequest request) {
        machineService.update(principal, name, request);

        return OkResponse.success();
    }

    /** Kunci baru, ditampilkan sekali — jangan sampai tersimpan di cache mana pun. */
    @PostMapping("/{name}/key")
    public ResponseEntity<MachineKeyResponse> createKey(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                                        @PathVariable String name) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(machineService.createKey(principal, name));
    }

    @DeleteMapping("/{name}/key")
    public OkResponse revokeKey(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                @PathVariable String name) {
        machineService.revokeKey(principal, name);

        return OkResponse.success();
    }

    @DeleteMapping("/{name}")
    public OkResponse delete(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String name) {
        machineService.delete(principal, name);

        return OkResponse.success();
    }
}
