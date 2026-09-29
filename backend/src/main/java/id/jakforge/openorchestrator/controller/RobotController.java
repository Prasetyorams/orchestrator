package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.dto.request.CreateRobotRequest;
import id.jakforge.openorchestrator.dto.request.HeartbeatRequest;
import id.jakforge.openorchestrator.dto.request.UpdateRobotRequest;
import id.jakforge.openorchestrator.dto.response.HeartbeatResponse;
import id.jakforge.openorchestrator.dto.response.OkResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.RobotService;
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

/** Robot. */
@RestController
@RequestMapping("/api/robots")
@RequiredArgsConstructor
public class RobotController {

    private final RobotService robotService;

    /** Dengan {@code folderId}: hanya robot yang ditugaskan ke folder itu. */
    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                             @RequestParam(required = false) String folderId) {
        return robotService.findAll(principal, folderId);
    }

    @GetMapping("/{name}")
    public Map<String, Object> findByName(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                          @PathVariable String name) {
        return robotService.findByName(principal, name);
    }

    /** Denyut JakRunner; badannya dibaca longgar. */
    @PostMapping("/{name}/heartbeat")
    public HeartbeatResponse recordHeartbeat(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                             @PathVariable String name,
                                             @RequestBody(required = false) Map<String, Object> body) {
        return robotService.recordHeartbeat(principal, name, HeartbeatRequest.fromBody(body));
    }

    @PostMapping
    public OkResponse create(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                             @Valid @RequestBody CreateRobotRequest request) {
        robotService.create(principal, request);

        return OkResponse.success();
    }

    /** Setelan robot, termasuk mesin dan akun Windows-nya untuk Robot Agent. */
    @PutMapping("/{name}")
    public OkResponse update(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String name,
                             @Valid @RequestBody UpdateRobotRequest request) {
        robotService.update(principal, name, request);

        return OkResponse.success();
    }

    @DeleteMapping("/{name}")
    public OkResponse delete(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String name) {
        robotService.delete(principal, name);

        return OkResponse.success();
    }
}
