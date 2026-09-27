package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.request.SaveCredentialRequest;
import id.jakforge.forgehub.dto.response.CredentialValueResponse;
import id.jakforge.forgehub.dto.response.OkResponse;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.AssetService;
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

/**
 * Jalur kredensial lama.
 *
 * <p>Sejak V3 kredensial adalah aset bertipe Credential; jalur ini tetap ada
 * untuk activity Get Credential dan klien lama, dan bekerja pada aset yang
 * sama dengan yang tampil di halaman Aset.
 */
@RestController
@RequestMapping("/api/credentials")
@RequiredArgsConstructor
public class CredentialController {

    private final AssetService assetService;

    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal ForgeHubPrincipal principal) {
        return assetService.findCredentials(principal);
    }

    @PostMapping
    public OkResponse save(@AuthenticationPrincipal ForgeHubPrincipal principal,
                           @Valid @RequestBody SaveCredentialRequest request) {
        assetService.saveCredential(principal, request);

        return OkResponse.success();
    }

    /** Dipanggil activity Get Credential. */
    @GetMapping("/{name}/value")
    public CredentialValueResponse getValue(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                            @PathVariable String name) {
        return assetService.getCredentialValue(principal, name);
    }

    @DeleteMapping("/{name}")
    public OkResponse delete(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String name) {
        assetService.deleteCredential(principal, name);

        return OkResponse.success();
    }
}
