package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.request.CreateBucketRequest;
import id.jakforge.forgehub.dto.request.MoveToFolderRequest;
import id.jakforge.forgehub.dto.request.UploadFileRequest;
import id.jakforge.forgehub.dto.response.OkResponse;
import id.jakforge.forgehub.dto.response.UploadFileResponse;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.BucketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

/** Ember penyimpanan (gudang berkas). */
@RestController
@RequestMapping("/api/buckets")
@RequiredArgsConstructor
public class BucketController {

    private final BucketService bucketService;

    /** Tanpa {@code folderId}: ember seluruh penyewa. */
    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                             @RequestParam(required = false) String folderId) {
        return bucketService.findAll(principal, folderId);
    }

    @PostMapping
    public OkResponse create(@AuthenticationPrincipal ForgeHubPrincipal principal,
                             @Valid @RequestBody CreateBucketRequest request) {
        bucketService.create(principal, request);

        return OkResponse.success();
    }

    @PutMapping("/{name}/folder")
    public OkResponse moveToFolder(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String name,
                                   @Valid @RequestBody MoveToFolderRequest request) {
        bucketService.moveToFolder(principal, name, request);

        return OkResponse.success();
    }

    @DeleteMapping("/{name}")
    public OkResponse delete(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String name) {
        bucketService.delete(principal, name);

        return OkResponse.success();
    }

    @GetMapping("/{name}/files")
    public List<Map<String, Object>> findFiles(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                               @PathVariable String name) {
        return bucketService.findFiles(principal, name);
    }

    @PostMapping("/{name}/files")
    public UploadFileResponse upload(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String name,
                                     @Valid @RequestBody UploadFileRequest request) {
        return bucketService.upload(principal, name, request);
    }

    @GetMapping("/{name}/files/{id}/content")
    public ResponseEntity<byte[]> downloadFile(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                               @PathVariable String name, @PathVariable String id) {
        return DownloadResponses.attachment(bucketService.getFileContent(principal, name, id));
    }

    @DeleteMapping("/{name}/files/{id}")
    public OkResponse deleteFile(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String name,
                                 @PathVariable String id) {
        bucketService.deleteFile(principal, name, id);

        return OkResponse.success();
    }
}
