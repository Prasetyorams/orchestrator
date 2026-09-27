package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.common.PageLimits;
import id.jakforge.forgehub.common.Uuids;
import id.jakforge.forgehub.dto.request.AddQueueItemRequest;
import id.jakforge.forgehub.dto.request.CreateQueueRequest;
import id.jakforge.forgehub.dto.request.MoveToFolderRequest;
import id.jakforge.forgehub.dto.request.NextQueueItemRequest;
import id.jakforge.forgehub.dto.request.QueueItemResultRequest;
import id.jakforge.forgehub.dto.response.CreatedResponse;
import id.jakforge.forgehub.dto.response.NextQueueItemResponse;
import id.jakforge.forgehub.dto.response.QueueItemResultResponse;
import id.jakforge.forgehub.model.QueueItemStatus;
import id.jakforge.forgehub.model.Severity;
import id.jakforge.forgehub.repository.AlertRepository;
import id.jakforge.forgehub.repository.QueueRepository;
import id.jakforge.forgehub.repository.QueueRepository.QueueItemSummary;
import id.jakforge.forgehub.repository.QueueRepository.QueueSettings;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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

    private static final String ITEM_NOT_FOUND = "Butir antrean tidak ada.";
    private static final String ALERT_SOURCE = "queues";

    private final QueueRepository queueRepository;
    private final AlertRepository alertRepository;
    private final FolderAccessService folderAccessService;

    /** Tanpa {@code folderId}: antrean seluruh penyewa. */
    public List<Map<String, Object>> findAll(ForgeHubPrincipal principal, String folderId) {
        return queueRepository.findAll(principal.tenantId(),
                folderAccessService.resolveFolderFilter(principal, folderId));
    }

    /** {@code folderId} kosong berarti folder bawaan. */
    @Transactional
    public void create(ForgeHubPrincipal principal, CreateQueueRequest request) {
        UUID tenantId = principal.tenantId();
        UUID folderId = folderAccessService.resolveFolderFilter(principal, request.folderId());

        if (queueRepository.existsByName(tenantId, request.name())) {
            throw ApiException.conflict("Antrean '" + request.name() + "' sudah ada.");
        }

        queueRepository.insert(tenantId, request.name(), request.description(),
                request.maxRetries(), request.acceptDuplicates(), folderId);
    }

    /** Butirnya tidak perlu ikut dipindah: butir mengikuti antreannya lewat nama. */
    public void moveToFolder(ForgeHubPrincipal principal, String name, MoveToFolderRequest request) {
        UUID folderId = folderAccessService.resolveFolderFilter(principal, request.folderId());

        if (folderId == null) throw ApiException.badRequest("folderId wajib diisi.");

        if (queueRepository.moveToFolder(principal.tenantId(), name, folderId) == 0) {
            throw ApiException.notFound("Antrean '" + name + "' tidak ada.");
        }
    }

    @Transactional
    public void delete(ForgeHubPrincipal principal, String name) {
        // Isinya ikut dihapus. Butir yang menggantung tanpa antrean induk tidak
        // akan pernah bisa dilihat lagi lewat jalan mana pun, tapi tetap
        // terhitung dalam angka apa pun yang menjumlahkan seluruh tabel.
        queueRepository.deleteItemsOfQueue(principal.tenantId(), name);

        if (queueRepository.deleteByName(principal.tenantId(), name) == 0) {
            throw ApiException.notFound("Antrean tidak ada.");
        }
    }

    public List<Map<String, Object>> findItems(ForgeHubPrincipal principal, String queueName, String status,
                                               Integer limit) {
        QueueItemStatus itemStatus = QueueItemStatus.parse(status);

        return queueRepository.findItems(principal.tenantId(), queueName,
                itemStatus == null ? null : itemStatus.name(),
                PageLimits.clamp(limit, DEFAULT_ITEM_PAGE_SIZE, MAX_ITEM_PAGE_SIZE));
    }

    @Transactional
    public CreatedResponse addItem(ForgeHubPrincipal principal, String queueName, AddQueueItemRequest request) {
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

    @Transactional
    public NextQueueItemResponse claimNextItem(ForgeHubPrincipal principal, String queueName,
                                               NextQueueItemRequest request) {
        return new NextQueueItemResponse(
                queueRepository.claimNextItem(principal.tenantId(), queueName, request.robotName()).orElse(null));
    }

    /**
     * Hasil pemrosesan satu butir.
     *
     * <p>Butir gagal dicoba lagi selama jatah percobaannya belum habis. Sesudah
     * habis ia berhenti dan menghasilkan peringatan — butir yang dicoba
     * selamanya adalah butir yang tidak pernah ketahuan rusaknya.
     */
    @Transactional
    public QueueItemResultResponse reportResult(ForgeHubPrincipal principal, String itemIdText,
                                                QueueItemResultRequest request) {
        UUID tenantId = principal.tenantId();
        UUID itemId = parseItemId(itemIdText);

        QueueItemStatus status = QueueItemStatus.parse(request.status());

        if (status == null || !status.isReportable()) {
            throw ApiException.badRequest("Status hasil tidak dikenal: '" + request.status() + "'.");
        }

        QueueItemSummary item = queueRepository.findItemSummary(tenantId, itemId)
                .orElseThrow(() -> ApiException.notFound(ITEM_NOT_FOUND));

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

    public void deleteItem(ForgeHubPrincipal principal, String itemId) {
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
