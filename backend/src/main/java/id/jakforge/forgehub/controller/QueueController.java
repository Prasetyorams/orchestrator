package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.request.AddQueueItemRequest;
import id.jakforge.forgehub.dto.request.CreateQueueRequest;
import id.jakforge.forgehub.dto.request.MoveToFolderRequest;
import id.jakforge.forgehub.dto.request.NextQueueItemRequest;
import id.jakforge.forgehub.dto.request.QueueItemResultRequest;
import id.jakforge.forgehub.dto.response.CreatedResponse;
import id.jakforge.forgehub.dto.response.NextQueueItemResponse;
import id.jakforge.forgehub.dto.response.OkResponse;
import id.jakforge.forgehub.dto.response.QueueItemResultResponse;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.QueueService;
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

/**
 * Antrean transaksi.
 *
 * <p>Menambah, mengambil, dan melaporkan butir dipanggil activity Orchestrator
 * di Studio, jadi badannya dibaca longgar.
 */
@RestController
@RequestMapping("/api/queues")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    /** Tanpa {@code folderId}: antrean seluruh penyewa. */
    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                             @RequestParam(required = false) String folderId) {
        return queueService.findAll(principal, folderId);
    }

    @PostMapping
    public OkResponse create(@AuthenticationPrincipal ForgeHubPrincipal principal,
                             @Valid @RequestBody CreateQueueRequest request) {
        queueService.create(principal, request);

        return OkResponse.success();
    }

    @PutMapping("/{name}/folder")
    public OkResponse moveToFolder(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String name,
                                   @Valid @RequestBody MoveToFolderRequest request) {
        queueService.moveToFolder(principal, name, request);

        return OkResponse.success();
    }

    @DeleteMapping("/{name}")
    public OkResponse delete(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String name) {
        queueService.delete(principal, name);

        return OkResponse.success();
    }

    /**
     * Butir sebuah antrean.
     *
     * <p>Dipetakan sebelum {@code /items/...} supaya niat urutannya terbaca,
     * meski Spring memang memilih pola yang lebih spesifik lebih dulu.
     */
    @GetMapping("/{name}/items")
    public List<Map<String, Object>> findItems(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                               @PathVariable String name,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) Integer limit) {
        return queueService.findItems(principal, name, status, limit);
    }

    @PostMapping("/{name}/items")
    public CreatedResponse addItem(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String name,
                                   @RequestBody(required = false) Map<String, Object> body) {
        return queueService.addItem(principal, name, AddQueueItemRequest.fromBody(body));
    }

    @PostMapping("/{name}/next")
    public NextQueueItemResponse claimNextItem(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                               @PathVariable String name,
                                               @RequestBody(required = false) Map<String, Object> body) {
        return queueService.claimNextItem(principal, name, NextQueueItemRequest.fromBody(body));
    }

    @PostMapping("/items/{id}/result")
    public QueueItemResultResponse reportResult(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                                @PathVariable String id,
                                                @RequestBody(required = false) Map<String, Object> body) {
        return queueService.reportResult(principal, id, QueueItemResultRequest.fromBody(body));
    }

    @DeleteMapping("/items/{id}")
    public OkResponse deleteItem(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String id) {
        queueService.deleteItem(principal, id);

        return OkResponse.success();
    }
}
