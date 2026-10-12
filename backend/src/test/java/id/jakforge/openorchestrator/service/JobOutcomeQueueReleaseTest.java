package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.repository.QueueRepository;
import id.jakforge.openorchestrator.repository.QueueRepository.HeldItem;
import id.jakforge.openorchestrator.repository.RobotRepository;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import id.jakforge.openorchestrator.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V15: job Robot Agent yang berakhir FAULTED/STOPPED melepas butir antrean yang masih dipegangnya. */
class JobOutcomeQueueReleaseTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();

    private final RecordingDatabase database = new RecordingDatabase();
    private final List<UUID> lookedUp = new ArrayList<>();
    private final UUID withRetriesLeft = UUID.randomUUID();
    private final UUID exhausted = UUID.randomUUID();

    private JobOutcomeService service(List<HeldItem> held) {
        QueueRepository queues = new QueueRepository(database) {
            @Override
            public List<HeldItem> findHeldByJob(UUID tenantId, UUID jobId) {
                lookedUp.add(jobId);
                return held;
            }
        };
        return new JobOutcomeService(new JobRepository(database), new RobotRepository(database, TestProperties.defaults()),
                new AlertRepository(database), new LogRepository(database), queues);
    }

    private static Map<String, Object> job(String state) {
        Map<String, Object> job = new HashMap<>();
        job.put("id", JOB.toString());
        job.put("tenantId", TENANT.toString());
        job.put("state", state);
        job.put("processName", "Tagihan");
        job.put("robotName", "R-01");
        return job;
    }

    private List<HeldItem> twoItems() {
        return List.of(new HeldItem(withRetriesLeft, "Tagihan", "INV-1", 0, 2),
                new HeldItem(exhausted, "Tagihan", "INV-2", 2, 2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAULTED", "STOPPED"})
    @DisplayName("FAULTED/STOPPED: masih ada jatah → kembali NEW; habis → ABANDONED; satu peringatan")
    void releasesHeldItems(String state) {
        service(twoItems()).afterFinished(job(state));

        assertEquals(List.of(JOB), lookedUp);

        List<Object> requeue = database.argumentsOf("SET status = 'NEW'");
        assertEquals(withRetriesLeft, requeue.get(2));
        assertTrue(((String) requeue.get(0)).contains("berakhir " + state));
        assertTrue(database.statementContaining("SET status = 'NEW'").contains("job_id = NULL"));

        List<Object> abandon = database.argumentsOf("ended_at = now()");
        assertEquals("ABANDONED", abandon.get(0));
        assertEquals(exhausted, abandon.get(4));

        List<String> releaseAlerts = new ArrayList<>();
        for (int i = 0; i < database.statements.size(); i++) {
            if (database.statements.get(i).contains("INSERT INTO alerts")
                    && "Butir antrean dilepas".equals(database.arguments.get(i)[2])) {
                releaseAlerts.add((String) database.arguments.get(i)[3]);
            }
        }
        assertEquals(1, releaseAlerts.size());
        assertTrue(releaseAlerts.getFirst().contains("1 dikembalikan ke antrean, 1 ditinggalkan"));
    }

    @Test
    @DisplayName("job berhasil: butir yang ditinggalnya tidak disentuh (bisa sudah diproses)")
    void successfulJobLeavesItems() {
        service(twoItems()).afterFinished(job("SUCCESSFUL"));

        assertTrue(lookedUp.isEmpty());
        assertTrue(database.statementsContaining("queue_items").isEmpty());
    }

    @Test
    @DisplayName("job gagal tanpa butir yang dipegang: tidak ada peringatan antrean")
    void nothingHeldNoAlert() {
        service(List.of()).afterFinished(job("FAULTED"));

        for (int i = 0; i < database.statements.size(); i++) {
            if (database.statements.get(i).contains("INSERT INTO alerts")) {
                assertTrue(!"Butir antrean dilepas".equals(database.arguments.get(i)[2]));
            }
        }
    }
}
