package id.jakforge.openorchestrator.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Perintah untuk robot, DITURUNKAN dari keadaan job di setiap denyut.
 *
 * <p>Tidak ada antrean perintah dan tidak ada tanda terima: selama syaratnya
 * berlaku, perintah yang sama diulang di setiap jawaban denyut, dan robot
 * aman menerimanya berkali-kali. Perintah yang hilang di jalan terkirim lagi
 * pada denyut berikutnya tanpa ada yang perlu mengingatnya.
 *
 * <p>Aturan jeda mengikuti usulan sisi robot (Studio
 * {@code docs/usulan-pause-job.md}): jeda dari dasbor dilanjutkan dari dasbor,
 * jeda yang ditekan di PC robot hanya dilanjutkan di PC itu, dan Stop
 * mengalahkan jeda.
 */
public final class JobCommands {

    public static final String STOP = "StopJob";
    public static final String KILL = "KillJob";
    public static final String PAUSE = "PauseJob";
    public static final String RESUME = "ResumeJob";

    /** Dijeda karena PauseJob dari dasbor. */
    public static final String SOURCE_DASHBOARD = "dashboard";

    /** Dijeda dengan tombol Jeda di PC robot. */
    public static final String SOURCE_LOCAL = "local";

    private JobCommands() {
    }

    /**
     * Jeda atau lanjut untuk satu job RUNNING.
     *
     * @param pauseRequested dasbor meminta job ini dijeda
     * @param pausedByRobot  robot melaporkan job ini sedang ditahan
     * @param pauseSource    siapa yang menjedanya, menurut robot
     * @return kosong kalau keadaan robot sudah sesuai permintaan
     */
    public static Optional<String> pauseCommand(boolean pauseRequested, boolean pausedByRobot, String pauseSource) {
        if (pauseRequested && !pausedByRobot) return Optional.of(PAUSE);

        // Jeda lokal tidak pernah dilanjutkan server: orang yang menekan Jeda
        // di depan PC-nya yang memutuskan kapan lanjut.
        if (!pauseRequested && pausedByRobot && SOURCE_DASHBOARD.equals(pauseSource)) return Optional.of(RESUME);

        return Optional.empty();
    }

    /** Sumber jeda dari robot, dibakukan. Yang tidak dikenal dianggap lokal — server tidak pernah melanjutkannya. */
    public static String normalizeSource(String text) {
        return text != null && SOURCE_DASHBOARD.equalsIgnoreCase(text.trim()) ? SOURCE_DASHBOARD : SOURCE_LOCAL;
    }

    /**
     * Job robot v1 yang mungkin perlu diberi perintah.
     *
     * @param kill diminta dimatikan paksa
     */
    public record V1Job(UUID id, JobState state, boolean pauseRequested, boolean kill) {
    }

    /**
     * Perintah untuk robot v1 (JakRunner, atau agent yang masuk dengan akun pengguna).
     *
     * <p>STOPPING mendapat StopJob, ditambah KillJob kalau diminta dimatikan
     * paksa. KillJob dikirim BERSAMA StopJob, tidak menggantikannya: robot v1
     * yang belum mengenal KillJob mengabaikannya dan tetap berhenti rapi.
     *
     * <p>RUNNING mendapat PauseJob atau ResumeJob menurut
     * {@link #pauseCommand}; job yang sedang dihentikan tidak diberi keduanya.
     *
     * @param pausedJobId job yang menurut denyut robot sedang ditahan, atau null
     * @param pauseSource sumber jedanya menurut robot
     */
    public static List<Map<String, Object>> forV1(List<V1Job> jobs, UUID pausedJobId, String pauseSource) {
        List<Map<String, Object>> commands = new ArrayList<>();

        for (V1Job job : jobs) {
            if (job.state() == JobState.STOPPING) {
                commands.add(command(STOP, job.id()));
                if (job.kill()) commands.add(command(KILL, job.id()));
            } else if (job.state() == JobState.RUNNING) {
                boolean paused = job.id().equals(pausedJobId);

                pauseCommand(job.pauseRequested(), paused, paused ? pauseSource : null)
                        .ifPresent(type -> commands.add(command(type, job.id())));
            }
        }

        return commands;
    }

    public static Map<String, Object> command(String type, UUID jobId) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", type);
        command.put("jobId", jobId.toString());
        return command;
    }
}
