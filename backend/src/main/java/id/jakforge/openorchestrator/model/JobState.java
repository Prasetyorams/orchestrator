package id.jakforge.openorchestrator.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Keadaan sebuah pekerjaan.
 *
 * <p>Enum, bukan untai bebas. Sebelumnya daftar ini hidup sebagai
 * {@code Set<String>} di dalam controller, dan setiap tempat yang perlu tahu
 * "apakah ini sudah selesai" menuliskan syaratnya sendiri — tiga tempat, tiga
 * kemungkinan berbeda begitu ada keadaan baru.
 *
 * <p>Nilainya disimpan di basis data sebagai teks dengan nama yang sama persis,
 * jadi {@code name()} boleh dipakai langsung sebagai nilai kolom.
 *
 * <p>ASSIGNED, PREPARING_SESSION, dan UNRESPONSIVE hanya dipakai job yang
 * diambil Robot Agent lewat /api/agent (kontrak v2). JakRunner dan klien v1
 * lain tidak pernah melihatnya: job mereka tetap PENDING → RUNNING → selesai.
 */
public enum JobState {
    PENDING,
    /** Diambil Robot Agent; lease penyiapan berjalan. */
    ASSIGNED,
    /** Agent sedang menyiapkan sesi Windows dan menyalakan Executor. */
    PREPARING_SESSION,
    RUNNING,
    /** Agent-nya hilang kontak; belum dianggap gagal. */
    UNRESPONSIVE,
    SUCCESSFUL,
    FAULTED,
    STOPPING,
    STOPPED;

    /** Keadaan yang dikenal kontrak v1 — yang boleh dilaporkan lewat /api/jobs/{id}/state. */
    public static final Set<JobState> V1_STATES = EnumSet.of(PENDING, RUNNING, SUCCESSFUL, FAULTED, STOPPING, STOPPED);

    /** Sedang dipegang robot: sudah diambil, belum selesai. */
    public static final Set<JobState> HELD = EnumSet.of(ASSIGNED, PREPARING_SESSION, RUNNING, STOPPING, UNRESPONSIVE);

    /** Sudah berakhir; tidak akan berubah lagi sendiri. */
    public boolean isFinished() {
        return this == SUCCESSFUL || this == FAULTED || this == STOPPED;
    }

    /**
     * Urai dari teks yang dikirim robot, atau null kalau tidak dikenal.
     *
     * <p>Mengembalikan null, bukan melempar: keadaan tak dikenal adalah
     * permintaan yang salah bentuk (400), bukan kerusakan server (500).
     */
    public static JobState parse(String text) {
        if (text == null || text.isBlank()) return null;

        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Untuk SQL: {@code ('ASSIGNED', 'PREPARING_SESSION', ...)}. */
    public static String sqlList(Set<JobState> states) {
        StringBuilder list = new StringBuilder("(");

        for (JobState state : states) {
            if (list.length() > 1) list.append(", ");
            list.append('\'').append(state.name()).append('\'');
        }

        return list.append(')').toString();
    }
}
