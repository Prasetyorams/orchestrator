package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.PageLimits;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.dto.request.AddQueueItemRequest;
import id.jakforge.openorchestrator.dto.request.CreateQueueRequest;
import id.jakforge.openorchestrator.dto.request.MoveToFolderRequest;
import id.jakforge.openorchestrator.dto.request.NextQueueItemRequest;
import id.jakforge.openorchestrator.dto.request.QueueItemResultRequest;
import id.jakforge.openorchestrator.dto.response.CreatedResponse;
import id.jakforge.openorchestrator.dto.response.NextQueueItemResponse;
import id.jakforge.openorchestrator.dto.response.QueueItemResultResponse;
import id.jakforge.openorchestrator.model.QueueItemStatus;
import id.jakforge.openorchestrator.model.Severity;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.QueueRepository;
import id.jakforge.openorchestrator.repository.QueueRepository.AbandonedItem;
import id.jakforge.openorchestrator.repository.QueueRepository.QueueItemSummary;
import id.jakforge.openorchestrator.repository.QueueRepository.QueueSettings;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Aturan tentang antrean transaksi.
 *
 * <p>Nama antrean unik untuk seluruh penyewa: robot memanggilnya lewat nama,
 * tanpa tahu foldernya.
 */
@Service
@RequiredArgsConstructor
public class QueueService {

    static final int DEFAULT_ITEM_PAGE_SIZE = 200;
    static final int MAX_ITEM_PAGE_SIZE = 2000;

    /** errorCode: hasil dilaporkan untuk butir yang tidak sedang IN_PROGRESS. */
    public static final String ITEM_NOT_IN_PROGRESS = "QUEUE_ITEM_NOT_IN_PROGRESS";
    /** errorCode: Executor melaporkan butir yang dipegang job lain. */
    public static final String ITEM_HELD_BY_OTHER_JOB = "QUEUE_ITEM_HELD_BY_OTHER_JOB";

    private static final String ITEM_NOT_FOUND = "Butir antrean tidak ada.";
    private static final String ALERT_SOURCE = "queues";

    private final QueueRepository queueRepository;
    private final AlertRepository alertRepository;
    private final FolderAccessService folderAccessService;
    private final OpenOrchestratorProperties properties;

    /** Tanpa {@code folderId}: antrean seluruh penyewa. */
    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal, String folderId) {
        return queueRepository.findAll(principal.tenantId(),
                folderAccessService.resolveFolderFilter(principal, folderId));
    }

    /** {@code folderId} kosong berarti folder bawaan. */
    @Transactional
    public void create(OpenOrchestratorPrincipal principal, CreateQueueRequest request) {
        UUID tenantId = principal.tenantId();
        UUID folderId = folderAccessService.resolveFolderFilter(principal, request.folderId());

        if (queueRepository.existsByName(tenantId, request.name())) {
            throw ApiException.conflict("Antrean '" + request.name() + "' sudah ada.");
        }

        queueRepository.insert(tenantId, request.name(), request.description(),
                request.maxRetries(), request.acceptDuplicates(), folderId);
    }

    /** Butirnya tidak perlu ikut dipindah: butir mengikuti antreannya lewat nama. */
    public void moveToFolder(OpenOrchestratorPrincipal principal, String name, MoveToFolderRequest request) {
        UUID folderId = folderAccessService.resolveFolderFilter(principal, request.folderId());

        if (folderId == null) throw ApiException.badRequest("folderId wajib diisi.");

        if (queueRepository.moveToFolder(principal.tenantId(), name, folderId) == 0) {
            throw ApiException.notFound("Antrean '" + name + "' tidak ada.");
        }
    }

    @Transactional
    public void delete(OpenOrchestratorPrincipal principal, String name) {
        // Isinya ikut dihapus. Butir yang menggantung tanpa antrean induk tidak
        // akan pernah bisa dilihat lagi lewat jalan mana pun, tapi tetap
        // terhitung dalam angka apa pun yang menjumlahkan seluruh tabel.
        queueRepository.deleteItemsOfQueue(principal.tenantId(), name);

        if (queueRepository.deleteByName(principal.tenantId(), name) == 0) {
            throw ApiException.notFound("Antrean tidak ada.");
        }
    }

    public List<Map<String, Object>> findItems(OpenOrchestratorPrincipal principal, String queueName, String status,
                                               Integer limit) {
        QueueItemStatus itemStatus = QueueItemStatus.parse(status);

        return queueRepository.findItems(principal.tenantId(), queueName,
                itemStatus == null ? null : itemStatus.name(),
                PageLimits.clamp(limit, DEFAULT_ITEM_PAGE_SIZE, MAX_ITEM_PAGE_SIZE));
    }

    @Transactional
    public CreatedResponse addItem(OpenOrchestratorPrincipal principal, String queueName, AddQueueItemRequest request) {
        UUID tenantId = principal.tenantId();
        QueueSettings settings = queueRepository.findSettings(tenantId, queueName)
                .orElseThrow(() -> ApiException.notFound("Antrean '" + queueName + "' tidak ada."));

        // Penolakan kembar hanya berlaku untuk butir yang BELUM selesai.
        // Referensi yang sama boleh muncul lagi besok; yang tidak boleh adalah
        // dua salinan menunggu diproses pada saat yang sama.
        if (!settings.acceptDuplicates() && request.reference() != null && !request.reference().isEmpty()
                && queueRepository.hasPendingDuplicate(tenantId, queueName, request.reference())) {

            throw ApiException.conflict("Butir dengan referensi '" + request.reference()
                    + "' sudah menunggu di antrean ini.");
        }

        UUID itemId = UUID.randomUUID();
        queueRepository.insertItem(itemId, tenantId, queueName, request.reference(), request.priority(),
                request.content());

        return CreatedResponse.of(itemId);
    }

    /**
     * Ambil butir berikutnya. Dengan token Executor (job Robot Agent) butirnya
     * dicatat milik job itu: kalau job-nya berakhir tanpa melaporkan hasil,
     * butirnya dilepas (JobOutcomeService), bukan tertahan selamanya.
     */
    @Transactional
    public NextQueueItemResponse claimNextItem(OpenOrchestratorPrincipal principal, String queueName,
                                               NextQueueItemRequest request) {
        UUID jobId = principal.isExecutor() ? principal.jobId() : null;

        return new NextQueueItemResponse(
                queueRepository.claimNextItem(principal.tenantId(), queueName, request.robotName(), jobId).orElse(null));
    }

    /**
     * Hasil pemrosesan satu butir.
     *
     * <p>Butir gagal dicoba lagi selama jatah percobaannya belum habis. Sesudah
     * habis ia berhenti dan menghasilkan peringatan — butir yang dicoba
     * selamanya adalah butir yang tidak pernah ketahuan rusaknya.
     */
    @Transactional
    public QueueItemResultResponse reportResult(OpenOrchestratorPrincipal principal, String itemIdText,
                                                QueueItemResultRequest request) {
        UUID tenantId = principal.tenantId();
        UUID itemId = parseItemId(itemIdText);

        QueueItemStatus status = QueueItemStatus.parse(request.status());

        if (status == null || !status.isReportable()) {
            throw ApiException.badRequest("Status hasil tidak dikenal: '" + request.status() + "'.");
        }

        QueueItemSummary item = queueRepository.findItemSummary(tenantId, itemId)
                .orElseThrow(() -> ApiException.notFound(ITEM_NOT_FOUND));

        // Hanya butir yang sedang diproses yang menerima hasil. Butir yang
        // sudah dilepas karena job-nya berakhir (atau ditinggalkan penyapu)
        // mungkin sedang dipegang pengambil lain; laporan terlambat tidak
        // boleh menimpa hasil mereka.
        if (!QueueItemStatus.IN_PROGRESS.name().equals(item.status())) {
            throw ApiException.conflict("Butir ini tidak sedang diproses (statusnya "
                    + item.status() + "); hasilnya tidak dicatat.").withCode(ITEM_NOT_IN_PROGRESS);
        }

        if (principal.isExecutor() && item.jobId() != null && !item.jobId().equals(principal.jobId())) {
            throw ApiException.conflict("Butir ini sedang dipegang job lain; hasilnya tidak dicatat.")
                    .withCode(ITEM_HELD_BY_OTHER_JOB);
        }

        if (status == QueueItemStatus.FAILED) {
            long maxRetries = queueRepository.findSettings(tenantId, item.queueName())
                    .map(QueueSettings::maxRetries)
                    .orElse(0);

            if (item.retries() < maxRetries) {
                queueRepository.requeueForRetry(tenantId, itemId, request.exception());

                return QueueItemResultResponse.retried(item.retries() + 1);
            }

            alertRepository.insert(tenantId, Severity.Warning, "Butir antrean gagal permanen",
                    "Butir '" + (item.reference() == null ? itemIdText : item.reference()) + "' di antrean "
                            + item.queueName() + " gagal setelah " + item.retries() + " percobaan ulang.",
                    ALERT_SOURCE);
        }

        queueRepository.completeItem(tenantId, itemId, status, request.output(), request.exception());

        return QueueItemResultResponse.completed();
    }

    /**
     * Penyapu: butir yang IN_PROGRESS lebih lama dari
     * {@code openorchestrator.queues.abandon-after} (bawaan 24 jam) menjadi
     * ABANDONED. Menolong butir yang tidak dipegang job Robot Agent — diambil
     * robot v1 atau pengguna — dan butir yang job-nya berhasil tanpa
     * melaporkan hasil. Satu peringatan per penyewa per putaran.
     *
     * @return jumlah butir yang ditinggalkan
     */
    @Transactional
    public int abandonStaleItems() {
        Duration after = properties.queues().abandonAfter();
        List<AbandonedItem> items = queueRepository.abandonStale(after.toSeconds(),
                "Lebih dari " + describe(after) + " berstatus IN_PROGRESS tanpa hasil; ditinggalkan otomatis.");

        Map<UUID, List<AbandonedItem>> byTenant = new LinkedHashMap<>();
        for (AbandonedItem item : items) byTenant.computeIfAbsent(item.tenantId(), id -> new ArrayList<>()).add(item);

        byTenant.forEach((tenantId, abandoned) -> {
            String queues = String.join(", ", new TreeSet<>(abandoned.stream().map(AbandonedItem::queueName).toList()));
            alertRepository.insert(tenantId, Severity.Warning, "Butir antrean ditinggalkan",
                    abandoned.size() + " butir di antrean " + queues + " lebih dari " + describe(after)
                            + " berstatus Diproses tanpa hasil, dan ditandai Abandoned.", ALERT_SOURCE);
        });

        return items.size();
    }

    static String describe(Duration duration) {
        if (duration.toMinutes() % 60 == 0 && duration.toHours() > 0) return duration.toHours() + " jam";
        return duration.toMinutes() + " menit";
    }

    public void deleteItem(OpenOrchestratorPrincipal principal, String itemId) {
        if (queueRepository.deleteItem(principal.tenantId(), parseItemId(itemId)) == 0) {
            throw ApiException.notFound(ITEM_NOT_FOUND);
        }
    }

    private static UUID parseItemId(String itemId) {
        UUID id = Uuids.parseOrNull(itemId);
        if (id == null) throw ApiException.notFound(ITEM_NOT_FOUND);
        return id;
    }
}
