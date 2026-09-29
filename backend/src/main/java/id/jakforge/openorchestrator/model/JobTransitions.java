package id.jakforge.openorchestrator.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Aturan perpindahan keadaan job yang DILAPORKAN robot.
 *
 * <p>Fungsi murni tanpa basis data: dari keadaan job saat ini dan laporannya,
 * putuskan apakah laporan itu diterapkan, diabaikan sebagai kiriman ulang,
 * atau ditolak (409). Ditulis terpisah supaya bisa diuji tuntas — urutan
 * laporan yang tertunda, terlambat, atau berulang adalah tempat kesalahan
 * semacam ini bersembunyi.
 *
 * <p>Prinsipnya, untuk kontrak v2 (Robot Agent):
 *
 * <ul>
 *   <li>{@code seq} yang sudah diterima (sama atau lebih kecil) tidak
 *       berefek apa pun, tapi dijawab 200: outbox agent boleh mengirim ulang
 *       sesukanya.</li>
 *   <li>Laporan AKHIR diterima dari keadaan mana pun yang belum selesai.
 *       Laporan RUNNING bisa saja hilang di jalan; hasil akhir yang ditolak
 *       karena itu berarti hasil asli terbuang.</li>
 *   <li>Laporan antara hanya boleh maju: ASSIGNED → PREPARING_SESSION →
 *       RUNNING. Selama STOPPING, laporan antara diterima tapi keadaannya
 *       tetap STOPPING.</li>
 *   <li>Job yang sudah selesai tidak berubah lagi — KECUALI kegagalannya
 *       disimpulkan Orchestrator (robot hilang, lease habis) dan belum ada
 *       percobaan ulang. Laporan asli robot lebih benar daripada tebakan
 *       server.</li>
 * </ul>
 */
public final class JobTransitions {

    /** Yang boleh dikirim agent sebagai keadaan job. */
    public static final Set<JobState> AGENT_REPORTABLE =
            EnumSet.of(JobState.PREPARING_SESSION, JobState.RUNNING, JobState.SUCCESSFUL, JobState.FAULTED,
                    JobState.STOPPED);

    public enum Verdict {
        /** Terapkan; {@link Decision#state()} adalah keadaan yang disimpan. */
        APPLY,
        /** Kiriman ulang: dijawab 200 tanpa mengubah apa pun. */
        DUPLICATE,
        /** 409: perpindahan yang tidak sah. */
        REJECT
    }

    /**
     * @param state     keadaan yang disimpan kalau APPLY — bisa berbeda dari yang dilaporkan
     * @param lateFinal laporan AKHIR yang ditolak karena job sudah selesai; dicatat di log job
     * @param revived   kegagalan kesimpulan server digantikan laporan asli
     */
    public record Decision(Verdict verdict, JobState state, boolean lateFinal, boolean revived) {

        static Decision apply(JobState state) {
            return new Decision(Verdict.APPLY, state, false, false);
        }

        static Decision revive(JobState state) {
            return new Decision(Verdict.APPLY, state, false, true);
        }

        static Decision duplicate(JobState current) {
            return new Decision(Verdict.DUPLICATE, current, false, false);
        }

        static Decision reject(JobState current, boolean lateFinal) {
            return new Decision(Verdict.REJECT, current, lateFinal, false);
        }
    }

    /**
     * Keadaan job saat laporan tiba.
     *
     * @param failureInferred kegagalannya disimpulkan Orchestrator, bukan dilaporkan robot
     * @param retried         sudah ada percobaan ulang untuk job ini
     * @param stopRequested   sudah ada yang menekan Stop
     */
    public record Snapshot(JobState state, boolean failureInferred, boolean retried, boolean stopRequested,
                           long lastSeq) {
    }

    private JobTransitions() {
    }

    /** Laporan agent v2 lewat /api/agent/jobs/{id}/state. {@code reported} harus di {@link #AGENT_REPORTABLE}. */
    public static Decision forAgent(Snapshot job, JobState reported, long seq) {
        JobState current = job.state();

        if (seq <= job.lastSeq()) return Decision.duplicate(current);

        if (current.isFinished()) {
            if (job.failureInferred() && !job.retried()) {
                return Decision.revive(reported.isFinished() ? reported : resumed(job, reported));
            }

            return Decision.reject(current, reported.isFinished());
        }

        if (reported.isFinished()) return Decision.apply(reported);

        return switch (current) {
            case STOPPING -> Decision.apply(JobState.STOPPING);
            case UNRESPONSIVE -> reported == JobState.RUNNING
                    ? Decision.apply(job.stopRequested() ? JobState.STOPPING : JobState.RUNNING)
                    : Decision.reject(current, false);
            case ASSIGNED, PREPARING_SESSION, RUNNING -> rank(reported) >= rank(current)
                    ? Decision.apply(reported)
                    : Decision.reject(current, false);
            default -> Decision.reject(current, false);
        };
    }

    /**
     * Laporan v1 lewat /api/jobs/{id}/state (JakRunner, agent yang masuk dengan akun pengguna).
     *
     * <p>Lebih longgar dari v2 — robot v1 tidak mengirim nomor urut — dengan dua
     * penjagaan yang diminta sisi robot ({@code jobs.state.guard}): RUNNING
     * sesudah STOPPING ditolak, dan job yang sudah selesai tidak dihidupkan
     * kembali, kecuali kegagalannya kesimpulan server.
     */
    public static Decision forV1(Snapshot job, JobState reported) {
        JobState current = job.state();

        if (current.isFinished()) {
            return job.failureInferred() && !job.retried()
                    ? Decision.revive(reported)
                    : Decision.reject(current, reported.isFinished());
        }

        if (current == JobState.STOPPING && (reported == JobState.RUNNING || reported == JobState.PENDING)) {
            return Decision.reject(current, false);
        }

        return Decision.apply(reported);
    }

    /** Job yang dihidupkan kembali oleh laporan antara: berhenti tetap berhenti. */
    private static JobState resumed(Snapshot job, JobState reported) {
        return job.stopRequested() ? JobState.STOPPING : reported;
    }

    private static int rank(JobState state) {
        return switch (state) {
            case ASSIGNED -> 1;
            case PREPARING_SESSION -> 2;
            case RUNNING -> 3;
            default -> 0;
        };
    }
}
