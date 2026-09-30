package id.jakforge.openorchestrator.model;

import id.jakforge.openorchestrator.model.JobCommands.V1Job;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static id.jakforge.openorchestrator.model.JobCommands.KILL;
import static id.jakforge.openorchestrator.model.JobCommands.PAUSE;
import static id.jakforge.openorchestrator.model.JobCommands.RESUME;
import static id.jakforge.openorchestrator.model.JobCommands.SOURCE_DASHBOARD;
import static id.jakforge.openorchestrator.model.JobCommands.SOURCE_LOCAL;
import static id.jakforge.openorchestrator.model.JobCommands.STOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobCommandsTest {

    private static final UUID JOB = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER = UUID.fromString("99999999-2222-3333-4444-555555555555");

    // -----------------------------------------------------------------
    // Aturan jeda (usulan sisi robot, docs/usulan-pause-job.md)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("dasbor meminta jeda, robot belum menahan: PauseJob")
    void pauseRequestedNotYetPaused() {
        assertEquals(Optional.of(PAUSE), JobCommands.pauseCommand(true, false, null));
    }

    @Test
    @DisplayName("dasbor meminta jeda dan robot sudah menahan — dari mana pun: tidak ada perintah")
    void pauseRequestedAlreadyPaused() {
        assertEquals(Optional.empty(), JobCommands.pauseCommand(true, true, SOURCE_DASHBOARD));
        assertEquals(Optional.empty(), JobCommands.pauseCommand(true, true, SOURCE_LOCAL));
    }

    @Test
    @DisplayName("jeda dari dasbor dibatalkan: ResumeJob")
    void resumeDashboardPause() {
        assertEquals(Optional.of(RESUME), JobCommands.pauseCommand(false, true, SOURCE_DASHBOARD));
    }

    @Test
    @DisplayName("jeda yang ditekan di PC robot tidak pernah dilanjutkan server")
    void localPauseIsNeverResumedByServer() {
        assertEquals(Optional.empty(), JobCommands.pauseCommand(false, true, SOURCE_LOCAL));
    }

    @Test
    @DisplayName("tidak diminta dan tidak ditahan: tidak ada perintah")
    void nothingToDo() {
        assertEquals(Optional.empty(), JobCommands.pauseCommand(false, false, null));
    }

    @Test
    @DisplayName("sumber jeda dibakukan; yang tidak dikenal dianggap lokal")
    void sourceIsNormalized() {
        assertEquals(SOURCE_DASHBOARD, JobCommands.normalizeSource(" Dashboard "));
        assertEquals(SOURCE_LOCAL, JobCommands.normalizeSource("local"));
        assertEquals(SOURCE_LOCAL, JobCommands.normalizeSource("remote"));
        assertEquals(SOURCE_LOCAL, JobCommands.normalizeSource(null));
    }

    // -----------------------------------------------------------------
    // Robot v1
    // -----------------------------------------------------------------

    @Test
    @DisplayName("v1: StopJob untuk yang sedang dihentikan, KillJob ikut kalau diminta dimatikan paksa")
    void v1StopAndKill() {
        assertEquals(List.of(command(STOP, JOB)),
                JobCommands.forV1(List.of(new V1Job(JOB, JobState.STOPPING, false, false)), null, null));

        // KillJob BERSAMA StopJob: robot yang belum mengenal KillJob tetap berhenti rapi.
        assertEquals(List.of(command(STOP, JOB), command(KILL, JOB)),
                JobCommands.forV1(List.of(new V1Job(JOB, JobState.STOPPING, false, true)), null, null));
    }

    @Test
    @DisplayName("v1: Stop mengalahkan jeda — job yang sedang dihentikan tidak diberi PauseJob")
    void v1StopBeatsPause() {
        assertEquals(List.of(command(STOP, JOB)),
                JobCommands.forV1(List.of(new V1Job(JOB, JobState.STOPPING, true, false)), null, null));
    }

    @Test
    @DisplayName("v1: PauseJob sampai robot melaporkan job ITU yang ditahan")
    void v1PauseUntilReported() {
        List<V1Job> jobs = List.of(new V1Job(JOB, JobState.RUNNING, true, false));

        assertEquals(List.of(command(PAUSE, JOB)), JobCommands.forV1(jobs, null, null));
        assertEquals(List.of(command(PAUSE, JOB)), JobCommands.forV1(jobs, OTHER, SOURCE_DASHBOARD));
        assertTrue(JobCommands.forV1(jobs, JOB, SOURCE_DASHBOARD).isEmpty());
    }

    @Test
    @DisplayName("v1: ResumeJob hanya untuk jeda dari dasbor yang sudah tidak diminta")
    void v1Resume() {
        List<V1Job> jobs = List.of(new V1Job(JOB, JobState.RUNNING, false, false));

        assertEquals(List.of(command(RESUME, JOB)), JobCommands.forV1(jobs, JOB, SOURCE_DASHBOARD));
        assertTrue(JobCommands.forV1(jobs, JOB, SOURCE_LOCAL).isEmpty());
        assertTrue(JobCommands.forV1(jobs, null, null).isEmpty());
    }

    @Test
    @DisplayName("perintah menyebut jobId sebagai teks, bentuk yang dibaca JakRunner")
    void commandShape() {
        assertEquals(Map.of("type", STOP, "jobId", JOB.toString()), command(STOP, JOB));
    }

    private static Map<String, Object> command(String type, UUID jobId) {
        return JobCommands.command(type, jobId);
    }
}
