package id.jakforge.forgehub.queue;

import id.jakforge.forgehub.common.NotFoundException;
import id.jakforge.forgehub.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/queues")
public class QueueController {

    private final QueueRepositories.Queues queues;
    private final QueueRepositories.Items items;

    public QueueController(QueueRepositories.Queues queues, QueueRepositories.Items items) {
        this.queues = queues;
        this.items = items;
    }

    public record QueueView(String id, String name, String description,
                            long newCount, long inProgressCount, long successCount, long failedCount) {
    }

    public record CreateQueue(@NotBlank String name, String description, Integer maxRetries) {
    }

    public record AddItem(String reference, String priority, String payload) {
    }

    public record CompleteItem(String status, String errorMessage) {
    }

    @GetMapping
    public List<QueueView> list() {
        var tenantId = CurrentUser.get().tenantId();

        return queues.findByTenantIdOrderByNameAsc(tenantId).stream()
                .map(q -> new QueueView(
                        q.getId().toString(), q.getName(), q.getDescription(),
                        items.countByTenantIdAndQueueIdAndStatus(tenantId, q.getId(), "NEW"),
                        items.countByTenantIdAndQueueIdAndStatus(tenantId, q.getId(), "IN_PROGRESS"),
                        items.countByTenantIdAndQueueIdAndStatus(tenantId, q.getId(), "SUCCESSFUL"),
                        items.countByTenantIdAndQueueIdAndStatus(tenantId, q.getId(), "FAILED")))
                .toList();
    }

    @PostMapping
    public QueueView create(@RequestBody CreateQueue request) {
        var tenantId = CurrentUser.get().tenantId();

        var queue = new Queue();
        queue.setTenantId(tenantId);
        queue.setName(request.name());
        queue.setDescription(request.description());
        if (request.maxRetries() != null) queue.setMaxRetries(request.maxRetries());

        queues.save(queue);

        return list().stream()
                .filter(v -> v.id().equals(queue.getId().toString()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Antrean"));
    }

    @GetMapping("/{queueId}/items")
    public List<QueueItem> itemsOf(@PathVariable String queueId) {
        var tenantId = CurrentUser.get().tenantId();
        return items.findByTenantIdAndQueueIdOrderByCreatedAtDesc(tenantId, UUID.fromString(queueId));
    }

    @PostMapping("/{queueId}/items")
    public QueueItem add(@PathVariable String queueId, @RequestBody AddItem request) {
        var tenantId = CurrentUser.get().tenantId();

        queues.findByIdAndTenantId(UUID.fromString(queueId), tenantId)
                .orElseThrow(() -> new NotFoundException("Antrean"));

        var item = new QueueItem();
        item.setTenantId(tenantId);
        item.setQueueId(UUID.fromString(queueId));
        item.setReference(request.reference());
        if (request.priority() != null) item.setPriority(request.priority());
        item.setPayload(request.payload());

        return items.save(item);
    }

    /**
     * Ambil satu item berikutnya untuk dikerjakan.
     *
     * Dibungkus transaksi dan langsung ditandai IN_PROGRESS di dalam
     * transaksi yang sama. Kalau penandaannya dilakukan belakangan lewat
     * permintaan terpisah, dua robot yang meminta bersamaan akan menerima
     * item yang sama dan mengerjakannya dua kali.
     */
    @PostMapping("/{queueId}/next")
    @Transactional
    public QueueItem next(@PathVariable String queueId) {
        var tenantId = CurrentUser.get().tenantId();

        var item = items.findFirstByTenantIdAndQueueIdAndStatusOrderByPriorityAscCreatedAtAsc(
                        tenantId, UUID.fromString(queueId), "NEW")
                .orElseThrow(() -> new NotFoundException("Item antrean yang siap dikerjakan"));

        item.setStatus("IN_PROGRESS");
        return items.save(item);
    }

    @PostMapping("/items/{itemId}/complete")
    @Transactional
    public QueueItem complete(@PathVariable String itemId, @RequestBody CompleteItem request) {
        var tenantId = CurrentUser.get().tenantId();

        var item = items.findById(UUID.fromString(itemId))
                .filter(i -> i.getTenantId().equals(tenantId))
                .orElseThrow(() -> new NotFoundException("Item antrean"));

        var status = request.status() == null ? "SUCCESSFUL" : request.status();
        item.setStatus(status);
        item.setErrorMessage(request.errorMessage());
        item.setProcessedAt(OffsetDateTime.now());

        if ("FAILED".equals(status)) item.setRetryCount(item.getRetryCount() + 1);

        return items.save(item);
    }
}
