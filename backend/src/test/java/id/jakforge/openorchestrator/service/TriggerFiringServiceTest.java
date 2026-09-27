package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.repository.ProcessRepository;
import id.jakforge.openorchestrator.repository.TriggerRepository;
import id.jakforge.openorchestrator.repository.TriggerRepository.DueTrigger;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Menjalankan pemicu yang jatuh tempo.
 *
 * <p>Pemicu dan proses palsu; pekerjaan, catatan, dan peringatan ditulis lewat
 * repositori asli ke basis data perekam, supaya yang diuji juga isi SQL-nya.
 */
class TriggerFiringServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final UUID tenantId = UUID.randomUUID();
    private final UUID folderId = UUID.randomUUID();

    private final RecordingDatabase database = new RecordingDatabase();
    private final FakeTriggerRepository triggers = new FakeTriggerRepository();
    private final FakeProcessRepository processes = new FakeProcessRepository();

    private final TriggerFiringService firingService = new TriggerFiringService(triggers, processes,
            new JobRepository(database), new LogRepository(database), new AlertRepository(database),
            new NextRunCalculator(Clock.fixed(NOW, ZoneOffset.UTC)));

    private DueTrigger dueTrigger(String cron, String priority) {
        return new DueTrigger(UUID.randomUUID(), tenantId, folderId, "Harian", "Tagihan", null, 30, cron, priority,
                "UTC");
    }

    @Test
    @DisplayName("pemicu jatuh tempo menjadwalkan pekerjaan di folder pemicunya dan mencatat jalan berikutnya")
    void dueTriggerSchedulesJob() {
        triggers.locked = Optional.of(dueTrigger(null, null));

        firingService.fireIfDue(triggers.locked.get().id());

        List<Object> jobArgs = database.argumentsOf("INSERT INTO jobs");
        assertEquals(tenantId, jobArgs.get(1));
        assertEquals(folderId, jobArgs.get(2));
        assertEquals("Tagihan", jobArgs.get(3));
        assertEquals("Trigger", jobArgs.get(6));
        assertEquals("Normal", jobArgs.get(7), "prioritas kosong berarti Normal");
        assertEquals("Dijadwalkan oleh pemicu 'Harian'.", jobArgs.get(8));

        assertEquals(List.of(OffsetDateTime.parse("2026-09-27T10:30:00Z")), triggers.recordedNextRuns);
        assertEquals(1, database.statementsContaining("INSERT INTO logs").size());
        assertTrue(triggers.disabledIds.isEmpty());
    }

    @Test
    @DisplayName("pemicu yang prosesnya sudah tidak ada dimatikan, dengan peringatan, tanpa pekerjaan")
    void missingProcessDisablesTrigger() {
        processes.exists = false;
        triggers.locked = Optional.of(dueTrigger(null, "High"));

        firingService.fireIfDue(triggers.locked.get().id());

        assertEquals(List.of(triggers.locked.get().id()), triggers.disabledIds);
        assertEquals("Pemicu 'Harian' menunjuk proses 'Tagihan' yang sudah tidak ada.",
                database.argumentsOf("INSERT INTO alerts").get(3));
        assertTrue(database.statementsContaining("INSERT INTO jobs").isEmpty());
    }

    @Test
    @DisplayName("cron yang tidak pernah cocok mematikan pemicunya")
    void impossibleCronDisablesTrigger() {
        triggers.locked = Optional.of(dueTrigger("0 0 31 2 *", "Normal"));

        firingService.fireIfDue(triggers.locked.get().id());

        assertEquals(1, triggers.disabledIds.size());
        assertTrue(database.statementsContaining("INSERT INTO jobs").isEmpty());
    }

    @Test
    @DisplayName("pemicu yang sudah diambil penjadwal lain, atau tidak lagi jatuh tempo, dilewati")
    void triggerNoLongerDueIsSkipped() {
        triggers.locked = Optional.empty();

        firingService.fireIfDue(UUID.randomUUID());

        assertTrue(database.statements.isEmpty());
        assertTrue(triggers.recordedNextRuns.isEmpty());
    }

    private static final class FakeTriggerRepository extends TriggerRepository {

        Optional<DueTrigger> locked = Optional.empty();
        final List<OffsetDateTime> recordedNextRuns = new ArrayList<>();
        final List<UUID> disabledIds = new ArrayList<>();

        FakeTriggerRepository() {
            super(null);
        }

        @Override
        public Optional<DueTrigger> lockIfDue(UUID triggerId) {
            return locked;
        }

        @Override
        public void recordRun(UUID triggerId, OffsetDateTime nextRunAt) {
            recordedNextRuns.add(nextRunAt);
        }

        @Override
        public void disable(UUID triggerId) {
            disabledIds.add(triggerId);
        }
    }

    private static final class FakeProcessRepository extends ProcessRepository {

        boolean exists = true;

        FakeProcessRepository() {
            super(null);
        }

        @Override
        public boolean existsInFolder(UUID tenantId, String name, UUID folderId) {
            return exists;
        }
    }
}
