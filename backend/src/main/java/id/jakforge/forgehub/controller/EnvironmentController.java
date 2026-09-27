package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.request.CreateEnvironmentRequest;
import id.jakforge.forgehub.dto.response.OkResponse;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.EnvironmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Lingkungan tempat robot dikelompokkan. */
@RestController
@RequestMapping("/api/environments")
@RequiredArgsConstructor
public class EnvironmentController {

    private final EnvironmentService environmentService;

    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal ForgeHubPrincipal principal) {
        return environmentService.findAll(principal);
    }

    @PostMapping
    public OkResponse create(@AuthenticationPrincipal ForgeHubPrincipal principal,
                             @Valid @RequestBody CreateEnvironmentRequest request) {
        environmentService.create(principal, request);

        return OkResponse.success();
    }

    @DeleteMapping("/{name}")
    public OkResponse delete(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String name) {
        environmentService.delete(principal, name);

        return OkResponse.success();
    }
}
