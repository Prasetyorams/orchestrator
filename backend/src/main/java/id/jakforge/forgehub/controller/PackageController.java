package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.request.PublishPackageRequest;
import id.jakforge.forgehub.dto.response.OkResponse;
import id.jakforge.forgehub.dto.response.PublishPackageResponse;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.PackageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

/** Paket automasi. */
@RestController
@RequestMapping("/api/packages")
@RequiredArgsConstructor
public class PackageController {

    private final PackageService packageService;

    /** Dengan {@code folderId}: paket yang dipakai proses di folder itu; tanpa itu: seluruh umpan. */
    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                             @RequestParam(required = false) String folderId) {
        return packageService.findAll(principal, folderId);
    }

    /** Dipanggil Studio, jadi badannya dibaca longgar. */
    @PostMapping
    public PublishPackageResponse publish(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                          @RequestBody(required = false) Map<String, Object> body) {
        return packageService.publish(principal, PublishPackageRequest.fromBody(body));
    }

    /** Unduh isi paket (.zip). */
    @GetMapping("/{name}/{version}/content")
    public ResponseEntity<byte[]> downloadContent(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                                  @PathVariable String name, @PathVariable String version) {
        return DownloadResponses.attachment(packageService.getContent(principal, name, version));
    }

    @DeleteMapping("/{name}/{version}")
    public OkResponse delete(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String name,
                             @PathVariable String version) {
        packageService.delete(principal, name, version);

        return OkResponse.success();
    }
}
