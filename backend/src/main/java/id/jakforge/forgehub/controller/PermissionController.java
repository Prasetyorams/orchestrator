package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.response.PermissionResourceResponse;
import id.jakforge.forgehub.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Katalog izin: sumber dan tindakan yang bisa dipilih di matriks layar Peran. */
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final RoleService roleService;

    @GetMapping
    public List<PermissionResourceResponse> getCatalog() {
        return roleService.permissionCatalog();
    }
}
