package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.dto.request.AssignRobotRequest;
import id.jakforge.openorchestrator.dto.request.AssignUserRequest;
import id.jakforge.openorchestrator.dto.request.FolderRequest;
import id.jakforge.openorchestrator.dto.response.CreatedResponse;
import id.jakforge.openorchestrator.dto.response.FolderMembersResponse;
import id.jakforge.openorchestrator.dto.response.FolderTreeResponse;
import id.jakforge.openorchestrator.dto.response.OkResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.FolderService;
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

/**
 * Folder dan penugasannya.
 *
 * <p>{@code /manage} dan {@code /personal} dipetakan sebagai jalur tetap,
 * bukan id: Spring memilih pola tetap lebih dulu daripada {@code /{id}}, jadi
 * keduanya tidak pernah terbaca sebagai folder bernama "manage".
 */
@RestController
@RequestMapping("/api/folders")
@RequiredArgsConstructor
public class FolderController {

    private final FolderService folderService;

    /** Isi bilah folder: folder yang boleh dilihat, dan Folder Saya. */
    @GetMapping
    public FolderTreeResponse getTree(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return folderService.getTree(principal);
    }

    /** Semua folder beserta isinya, untuk halaman pengelolaan. */
    @GetMapping("/manage")
    public List<Map<String, Object>> findAllForManagement(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return folderService.findAllForManagement(principal);
    }

    /**
     * Dibaca dari {@code Map}: {@code parentId} yang tidak disebut berbeda
     * artinya dari {@code parentId} kosong — lihat {@link FolderRequest}.
     */
    @PostMapping
    public CreatedResponse create(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return folderService.create(principal, FolderRequest.fromBody(body));
    }

    /** Folder Saya, dibuat saat pertama kali dibuka. */
    @PostMapping("/personal")
    public Map<String, Object> getOrCreatePersonalFolder(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return folderService.getOrCreatePersonalFolder(principal);
    }

    @PutMapping("/{id}")
    public OkResponse update(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String id,
                             @RequestBody(required = false) Map<String, Object> body) {
        folderService.update(principal, id, FolderRequest.fromBody(body));

        return OkResponse.success();
    }

    @DeleteMapping("/{id}")
    public OkResponse delete(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String id) {
        folderService.delete(principal, id);

        return OkResponse.success();
    }

    // -----------------------------------------------------------------
    // Penugasan
    // -----------------------------------------------------------------

    @GetMapping("/{id}/members")
    public FolderMembersResponse getMembers(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                            @PathVariable String id) {
        return folderService.getMembers(principal, id);
    }

    @PostMapping("/{id}/users")
    public OkResponse assignUser(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String id,
                                 @Valid @RequestBody AssignUserRequest request) {
        folderService.assignUser(principal, id, request);

        return OkResponse.success();
    }

    @DeleteMapping("/{id}/users/{username}")
    public OkResponse unassignUser(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String id,
                                   @PathVariable String username) {
        folderService.unassignUser(principal, id, username);

        return OkResponse.success();
    }

    @PostMapping("/{id}/robots")
    public OkResponse assignRobot(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String id,
                                  @Valid @RequestBody AssignRobotRequest request) {
        folderService.assignRobot(principal, id, request);

        return OkResponse.success();
    }

    @DeleteMapping("/{id}/robots/{name}")
    public OkResponse unassignRobot(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String id,
                                    @PathVariable String name) {
        folderService.unassignRobot(principal, id, name);

        return OkResponse.success();
    }
}
