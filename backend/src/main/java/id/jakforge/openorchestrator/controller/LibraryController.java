package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.audit.AuditInterceptor;
import id.jakforge.openorchestrator.dto.response.OkResponse;
import id.jakforge.openorchestrator.dto.response.UploadLibraryResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.LibraryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Feed library (V20): library OpenStudio yang dipasang robot sebelum menjalankan proses yang memakainya. */
@RestController
@RequestMapping("/api/libraries")
@RequiredArgsConstructor
public class LibraryController {

    private final LibraryService libraryService;

    /** Satu baris per versi; {@code search} mencocokkan Id dan keterangan. */
    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                             @RequestParam(required = false) String search) {
        return libraryService.findAll(principal, search);
    }

    /** Bagian {@code file} berisi .nupkg; Id dan versinya dibaca dari .nuspec-nya. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadLibraryResponse upload(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                        @RequestPart(value = "file", required = false) MultipartFile file,
                                        HttpServletRequest request) {
        UploadLibraryResponse response = libraryService.upload(principal, file);

        // Id dan versinya baru diketahui sesudah .nuspec-nya dibaca.
        AuditInterceptor.resolvedTarget(request, response.id() + " · " + response.version());

        return response;
    }

    /** Unduh .nupkg apa adanya. */
    @GetMapping("/{name}/{version}/content")
    public ResponseEntity<byte[]> downloadContent(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                                  @PathVariable String name, @PathVariable String version) {
        return DownloadResponses.attachment(principal.isAgent()
                ? libraryService.getContentForAgent(principal, name, version)
                : libraryService.getContent(principal, name, version));
    }

    @DeleteMapping("/{name}/{version}")
    public OkResponse delete(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String name,
                             @PathVariable String version) {
        libraryService.delete(principal, name, version);

        return OkResponse.success();
    }
}
