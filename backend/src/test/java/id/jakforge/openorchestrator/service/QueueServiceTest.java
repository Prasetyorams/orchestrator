package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.dto.request.NextQueueItemRequest;
import id.jakforge.openorchestrator.dto.request.QueueItemResultRequest;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.QueueRepository;
import id.jakforge.openorchestrator.repository.QueueRepository.AbandonedItem;
import id.jakforge.openorchestrator.repository.QueueRepository.QueueItemSummary;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import id.jakforge.openorchestrator.support.TestFolderAccess;
import id.jakforge.openorchestrator.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V15: butir antrean dicatat milik job-nya, dan hasil hanya diterima untuk butir yang sedang diproses. */
class QueueServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final String ITEM = UUID.randomUUID().toString();

    private final RecordingDatabase database = new RecordingDatabase();

    private final OpenOrchestratorPrincipal user =
            new OpenOrchestratorPrincipal(UUID.randomUUID(), TENANT, "fajar", "Administrator");
    private final OpenOrchestratorPrincipal executor =
            OpenOrchestratorPrincipal.executor(UUID.randomUUID(), TENANT, "R-01", JOB, UUID.randomUUID());

    private QueueService service(QueueItemSummary item, List<AbandonedItem> abandoned) {
        QueueRepository queues = new QueueRepository(database) {
            @Override
            public Optional<QueueItemSummary> findItemSummary(UUID tenantId, UUID itemId) {
                return Optional.ofNullable(item);
            }

            @Override
            public List<AbandonedItem> abandonStale(long olderThanSeconds, String exception) {
                super.abandonStale(olderThanSeconds, exception);
                return abandoned;
            }
        };
        return new QueueService(queues, new AlertRepository(database), TestFolderAccess.permitAll(),
                TestProperties.defaults());
    }

    private QueueService service(QueueItemSummary item) {
        return service(item, List.of());
    }

    private static QueueItemSummary item(String status, UUID jobId) {
        return new QueueItemSummary("Tagihan", 0, "INV-1", status, jobId);
    }

    private static QueueItemResultRequest result(String status) {
        return QueueItemResultRequest.fromBody(Map.of("status", status));
    }

    @Test
    @DisplayName("diambil Executor: job_id diisi dengan job-nya")
    void executorClaimRecordsJob() {
        service(null).claimNextItem(executor, "Tagihan", NextQueueItemRequest.fromBody(Map.of("robotName", "R-01")));

        String sql = database.statementContaining("UPDATE queue_items");
        assertTrue(sql.contains("job_id = ?"));
        assertEquals(JOB, database.argumentsOf("UPDATE queue_items").get(1));
    }

    @Test
    @DisplayName("diambil pengguna atau robot v1: job_id NULL")
    void userClaimHasNoJob() {
        service(null).claimNextItem(user, "Tagihan", NextQueueItemRequest.fromBody(Map.of("robotName", "PC-01")));

        assertNull(database.argumentsOf("UPDATE queue_items").get(1));
    }

    @Test
    @DisplayName("hasil untuk butir yang sudah dilepas (NEW): 409, tidak ada yang ditulis")
    void resultForReleasedItemRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> service(item("NEW", null)).reportResult(executor, ITEM, result("SUCCESSFUL")));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals(QueueService.ITEM_NOT_IN_PROGRESS, error.errorCode());
        assertTrue(database.statementsContaining("UPDATE queue_items").isEmpty());
    }

    @Test
    @DisplayName("hasil untuk butir yang sudah ABANDONED: 409")
    void resultForAbandonedItemRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> service(item("ABANDONED", null)).reportResult(user, ITEM, result("SUCCESSFUL")));

        assertEquals(QueueService.ITEM_NOT_IN_PROGRESS, error.errorCode());
    }

    @Test
    @DisplayName("Executor melaporkan butir yang dipegang job lain: 409")
    void resultFromOtherJobRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> service(item("IN_PROGRESS", UUID.randomUUID())).reportResult(executor, ITEM, result("SUCCESSFUL")));

        assertEquals(QueueService.ITEM_HELD_BY_OTHER_JOB, error.errorCode());
        assertTrue(database.statementsContaining("UPDATE queue_items").isEmpty());
    }

    @Test
    @DisplayName("butir yang sedang diproses job ini: hasilnya dicatat")
    void resultFromOwningJobAccepted() {
        service(item("IN_PROGRESS", JOB)).reportResult(executor, ITEM, result("SUCCESSFUL"));

        assertEquals(1, database.statementsContaining("ended_at = now()").size());
    }

    @Test
    @DisplayName("pengguna boleh melaporkan butir yang dipegang job (tanpa token job, tidak bisa dicocokkan)")
    void userMayReportJobItem() {
        service(item("IN_PROGRESS", JOB)).reportResult(user, ITEM, result("SUCCESSFUL"));

        assertEquals(1, database.statementsContaining("ended_at = now()").size());
    }

    @Test
    @DisplayName("penyapu: batas 24 jam dikirim dalam detik; satu peringatan per penyewa")
    void sweeperAlertsOncePerTenant() {
        UUID other = UUID.randomUUID();
        int abandoned = service(null, List.of(
                new AbandonedItem(TENANT, "Tagihan", "A"),
                new AbandonedItem(TENANT, "Faktur", "B"),
                new AbandonedItem(other, "Tagihan", "C"))).abandonStaleItems();

        assertEquals(3, abandoned);
        assertEquals(86_400L, database.argumentsOf("SET status = 'ABANDONED'").get(1));
        List<String> alerts = database.statementsContaining("INSERT INTO alerts");
        assertEquals(2, alerts.size());
    }

    @Test
    @DisplayName("penyapu tanpa butir tertahan: tidak ada peringatan")
    void sweeperQuietWhenNothing() {
        assertEquals(0, service(null).abandonStaleItems());
        assertTrue(database.statementsContaining("INSERT INTO alerts").isEmpty());
    }

    @Test
    @DisplayName("keterangan batas waktu: jam bila bulat, selain itu menit")
    void describesDuration() {
        assertEquals("24 jam", QueueService.describe(Duration.ofHours(24)));
        assertEquals("90 menit", QueueService.describe(Duration.ofMinutes(90)));
    }
}
