package id.jakforge.openorchestrator.model;

import java.util.List;
import java.util.Locale;

/**
 * Apa yang memulai sebuah jalan automasi (V14): job Orchestrator, atau jalan
 * lokal Open Assistant di PC attended — tombol Play ({@link #MANUAL}) atau
 * jadwal lokal yang sengaja tidak disinkronkan ke Orchestrator
 * ({@link #LOCAL_SCHEDULE}).
 *
 * <p>Dipakai di dua tempat: pemicu baris catatan ({@code logs.run_trigger}),
 * dan pemicu "sibuk lokal" di denyut robot ({@code robots.busy_local_trigger},
 * hanya yang lokal).
 */
public final class RunTriggers {

    public static final String JOB = "job";
    public static final String MANUAL = "manual";
    public static final String LOCAL_SCHEDULE = "local-schedule";

    public static final List<String> ALL = List.of(JOB, MANUAL, LOCAL_SCHEDULE);

    private RunTriggers() {
    }

    /**
     * Bentuk baku: "Local-Schedule", "local_schedule", dan "localSchedule"
     * menjadi "local-schedule". Null untuk yang kosong atau tidak dikenal —
     * robot yang lebih baru boleh mengirim jenis yang belum dikenal, dan baris
     * catatannya tidak boleh ditolak karenanya.
     */
    public static String normalize(String text) {
        if (text == null) return null;

        String compact = text.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");

        return switch (compact) {
            case "job" -> JOB;
            case "manual" -> MANUAL;
            case "localschedule" -> LOCAL_SCHEDULE;
            default -> null;
        };
    }

    /** Hanya pemicu jalan lokal ({@link #MANUAL}, {@link #LOCAL_SCHEDULE}); selain itu null. */
    public static String normalizeLocal(String text) {
        String trigger = normalize(text);
        return JOB.equals(trigger) ? null : trigger;
    }
}
