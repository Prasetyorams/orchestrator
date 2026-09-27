package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.request.AssignRobotRequest;
import id.jakforge.forgehub.dto.request.AssignUserRequest;
import id.jakforge.forgehub.dto.request.FolderRequest;
import id.jakforge.forgehub.dto.response.CreatedResponse;
import id.jakforge.forgehub.dto.response.FolderMembersResponse;
import id.jakforge.forgehub.dto.response.FolderTreeResponse;
import id.jakforge.forgehub.dto.response.OkResponse;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.FolderService;
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
    public FolderTreeResponse getTree(@AuthenticationPrincipal ForgeHubPrincipal principal) {
        return folderService.getTree(principal);
    }

    /** Semua folder beserta isinya, untuk halaman pengelolaan. */
    @GetMapping("/manage")
    public List<Map<String, Object>> findAllForManagement(@AuthenticationPrincipal ForgeHubPrincipal principal) {
        return folderService.findAllForManagement(principal);
    }

    /**
     * Dibaca dari {@code Map}: {@code parentId} yang tidak disebut berbeda
     * artinya dari {@code parentId} kosong — lihat {@link FolderRequest}.
     */
    @PostMapping
    public CreatedResponse create(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return folderService.create(principal, FolderRequest.fromBody(body));
    }

    /** Folder Saya, dibuat saat pertama kali dibuka. */
    @PostMapping("/personal")
    public Map<String, Object> getOrCreatePersonalFolder(@AuthenticationPrincipal ForgeHubPrincipal principal) {
        return folderService.getOrCreatePersonalFolder(principal);
    }

    @PutMapping("/{id}")
    public OkResponse update(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String id,
                             @RequestBody(required = false) Map<String, Object> body) {
        folderService.update(principal, id, FolderRequest.fromBody(body));

        return OkResponse.success();
    }

    @DeleteMapping("/{id}")
    public OkResponse delete(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String id) {
        folderService.delete(principal, id);

        return OkResponse.success();
    }

    // -----------------------------------------------------------------
    // Penugasan
    // -----------------------------------------------------------------

    @GetMapping("/{id}/members")
    public FolderMembersResponse getMembers(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                            @PathVariable String id) {
        return folderService.getMembers(principal, id);
    }

    @PostMapping("/{id}/users")
    public OkResponse assignUser(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String id,
                                 @Valid @RequestBody AssignUserRequest request) {
        folderService.assignUser(principal, id, request);

        return OkResponse.success();
    }

    @DeleteMapping("/{id}/users/{username}")
    public OkResponse unassignUser(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String id,
                                   @PathVariable String username) {
        folderService.unassignUser(principal, id, username);

        return OkResponse.success();
    }

    @PostMapping("/{id}/robots")
    public OkResponse assignRobot(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String id,
                                  @Valid @RequestBody AssignRobotRequest request) {
        folderService.assignRobot(principal, id, request);

        return OkResponse.success();
    }

    @DeleteMapping("/{id}/robots/{name}")
    public OkResponse unassignRobot(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String id,
                                    @PathVariable String name) {
        folderService.unassignRobot(principal, id, name);

        return OkResponse.success();
    }
}
