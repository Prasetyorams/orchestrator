package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.dto.request.CreateRoleRequest;
import id.jakforge.openorchestrator.dto.request.UpdateRoleRequest;
import id.jakforge.openorchestrator.dto.response.OkResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.RoleService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Peran kustom. Daftarnya juga dibaca layar Pengguna. */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return roleService.findAll(principal);
    }

    @PostMapping
    public OkResponse create(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                             @Valid @RequestBody CreateRoleRequest request) {
        roleService.create(principal, request);

        return OkResponse.success();
    }

    /** {@code name} di badan yang berbeda dari jalurnya berarti ganti nama. */
    @PutMapping("/{name}")
    public OkResponse update(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String name,
                             @Valid @RequestBody UpdateRoleRequest request) {
        roleService.update(principal, name, request);

        return OkResponse.success();
    }

    @DeleteMapping("/{name}")
    public OkResponse delete(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String name) {
        roleService.delete(principal, name);

        return OkResponse.success();
    }
}
