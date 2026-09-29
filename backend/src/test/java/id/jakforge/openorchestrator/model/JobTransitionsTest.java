package id.jakforge.openorchestrator.model;

import id.jakforge.openorchestrator.model.JobTransitions.Decision;
import id.jakforge.openorchestrator.model.JobTransitions.Snapshot;
import id.jakforge.openorchestrator.model.JobTransitions.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static id.jakforge.openorchestrator.model.JobState.ASSIGNED;
import static id.jakforge.openorchestrator.model.JobState.FAULTED;
import static id.jakforge.openorchestrator.model.JobState.PENDING;
import static id.jakforge.openorchestrator.model.JobState.PREPARING_SESSION;
import static id.jakforge.openorchestrator.model.JobState.RUNNING;
import static id.jakforge.openorchestrator.model.JobState.STOPPED;
import static id.jakforge.openorchestrator.model.JobState.STOPPING;
import static id.jakforge.openorchestrator.model.JobState.SUCCESSFUL;
import static id.jakforge.openorchestrator.model.JobState.UNRESPONSIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobTransitionsTest {

    private static Snapshot job(JobState state, long lastSeq) {
        return new Snapshot(state, false, false, false, lastSeq);
    }

    private static Snapshot inferred(JobState state, boolean retried) {
        return new Snapshot(state, true, retried, false, 4);
    }

    // -----------------------------------------------------------------
    // v2 (Robot Agent)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("jalan normal: ASSIGNED → PREPARING_SESSION → RUNNING → SUCCESSFUL")
    void happyPath() {
        assertEquals(apply(PREPARING_SESSION), JobTransitions.forAgent(job(ASSIGNED, 0), PREPARING_SESSION, 1));
        assertEquals(apply(RUNNING), JobTransitions.forAgent(job(PREPARING_SESSION, 1), RUNNING, 2));
        assertEquals(apply(RUNNING), JobTransitions.forAgent(job(RUNNING, 2), RUNNING, 3), "laporan kemajuan");
        assertEquals(apply(SUCCESSFUL), JobTransitions.forAgent(job(RUNNING, 3), SUCCESSFUL, 4));
    }

    @Test
    @DisplayName("seq yang sama atau lebih kecil adalah kiriman ulang: 200 tanpa efek")
    void duplicateSeq() {
        assertEquals(Verdict.DUPLICATE, JobTransitions.forAgent(job(RUNNING, 5), SUCCESSFUL, 5).verdict());
        assertEquals(Verdict.DUPLICATE, JobTransitions.forAgent(job(RUNNING, 5), RUNNING, 3).verdict());
        assertEquals(Verdict.DUPLICATE, JobTransitions.forAgent(job(SUCCESSFUL, 9), SUCCESSFUL, 9).verdict(),
                "kiriman ulang laporan akhir tidak dijawab 409");
    }

    @Test
    @DisplayName("laporan akhir diterima walau laporan RUNNING-nya hilang di jalan")
    void finalFromAnyUnfinishedState() {
        assertEquals(apply(SUCCESSFUL), JobTransitions.forAgent(job(ASSIGNED, 0), SUCCESSFUL, 7));
        assertEquals(apply(FAULTED), JobTransitions.forAgent(job(PREPARING_SESSION, 1), FAULTED, 2));
        assertEquals(apply(STOPPED), JobTransitions.forAgent(job(STOPPING, 2), STOPPED, 3));
        assertEquals(apply(SUCCESSFUL), JobTransitions.forAgent(job(UNRESPONSIVE, 3), SUCCESSFUL, 4),
                "jaringan putus, job selesai selama putus, laporan akhir datang dari outbox");
    }

    @Test
    @DisplayName("laporan antara hanya boleh maju")
    void intermediateOnlyForward() {
        Decision backwards = JobTransitions.forAgent(job(RUNNING, 3), PREPARING_SESSION, 4);

        assertEquals(Verdict.REJECT, backwards.verdict());
        assertFalse(backwards.lateFinal());
        assertEquals(Verdict.REJECT, JobTransitions.forAgent(job(PENDING, 0), RUNNING, 1).verdict(),
                "job yang belum diambil tidak bisa dilaporkan");
    }

    @Test
    @DisplayName("selama STOPPING, laporan antara diterima tapi keadaannya tetap STOPPING")
    void stoppingStaysStopping() {
        assertEquals(apply(STOPPING), JobTransitions.forAgent(job(STOPPING, 3), RUNNING, 4));
    }

    @Test
    @DisplayName("UNRESPONSIVE pulih menjadi RUNNING — atau STOPPING kalau sempat diminta berhenti")
    void unresponsiveRecovers() {
        assertEquals(apply(RUNNING), JobTransitions.forAgent(job(UNRESPONSIVE, 3), RUNNING, 4));

        Snapshot stopped = new Snapshot(UNRESPONSIVE, false, false, true, 3);
        assertEquals(apply(STOPPING), JobTransitions.forAgent(stopped, RUNNING, 4));

        assertEquals(Verdict.REJECT, JobTransitions.forAgent(job(UNRESPONSIVE, 3), PREPARING_SESSION, 4).verdict());
    }

    @Test
    @DisplayName("job yang selesai tidak berubah lagi; laporan akhir yang terlambat ditandai untuk dicatat")
    void finishedIsFinal() {
        Decision late = JobTransitions.forAgent(job(FAULTED, 4), SUCCESSFUL, 5);

        assertEquals(Verdict.REJECT, late.verdict());
        assertTrue(late.lateFinal());
        assertFalse(JobTransitions.forAgent(job(SUCCESSFUL, 4), RUNNING, 5).lateFinal());
    }

    @Test
    @DisplayName("kegagalan kesimpulan server digantikan laporan asli selama belum diulang (W2)")
    void inferredFailureIsOverwritten() {
        Decision real = JobTransitions.forAgent(inferred(FAULTED, false), SUCCESSFUL, 5);

        assertEquals(Verdict.APPLY, real.verdict());
        assertEquals(SUCCESSFUL, real.state());
        assertTrue(real.revived());

        Decision stillRunning = JobTransitions.forAgent(inferred(FAULTED, false), RUNNING, 5);
        assertEquals(RUNNING, stillRunning.state(), "agent kembali dan job masih berjalan");

        Decision afterRetry = JobTransitions.forAgent(inferred(FAULTED, true), SUCCESSFUL, 5);
        assertEquals(Verdict.REJECT, afterRetry.verdict(), "sudah diulang: hasil lama tidak menimpa");
        assertTrue(afterRetry.lateFinal());
    }

    // -----------------------------------------------------------------
    // v1 (jobs.state.guard)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("v1: RUNNING sesudah STOPPING ditolak, laporan akhir sesudah STOPPING diterima")
    void v1StoppingGuard() {
        assertEquals(Verdict.REJECT, JobTransitions.forV1(job(STOPPING, 0), RUNNING).verdict());
        assertEquals(apply(SUCCESSFUL), JobTransitions.forV1(job(STOPPING, 0), SUCCESSFUL));
        assertEquals(apply(RUNNING), JobTransitions.forV1(job(RUNNING, 0), RUNNING));
    }

    @Test
    @DisplayName("v1: job selesai tidak dihidupkan kembali, kecuali kegagalannya kesimpulan server")
    void v1FinishedIsFinal() {
        assertEquals(Verdict.REJECT, JobTransitions.forV1(job(SUCCESSFUL, 0), RUNNING).verdict());
        assertTrue(JobTransitions.forV1(job(STOPPED, 0), FAULTED).lateFinal());

        Decision robotWasOnlySilent = JobTransitions.forV1(inferred(FAULTED, false), SUCCESSFUL);
        assertEquals(SUCCESSFUL, robotWasOnlySilent.state());
        assertTrue(robotWasOnlySilent.revived());
    }

    private static Decision apply(JobState state) {
        return new Decision(Verdict.APPLY, state, false, false);
    }
}
