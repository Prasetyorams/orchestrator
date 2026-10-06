package id.jakforge.openorchestrator.model;

/**
 * "Sibuk lokal" (V14): automasi yang sedang dijalankan Open Assistant di PC
 * attended di luar Orchestrator — tombol Play, atau jadwal lokal. Disebut
 * robot di denyutnya ({@code busyLocal}, {@code busyLocalName},
 * {@code busyLocalTrigger}); selama itu robotnya tidak diberi job.
 *
 * @param name    nama automasinya; boleh null
 * @param trigger {@link RunTriggers#MANUAL} atau {@link RunTriggers#LOCAL_SCHEDULE}; null kalau tidak disebut
 */
public record LocalRun(String name, String trigger) {

    /** Panjang kolom robots.busy_local_name. */
    public static final int MAX_NAME_LENGTH = 200;

    /** Null kalau robotnya tidak sibuk lokal — termasuk robot lama yang tidak mengirim medannya. */
    public static LocalRun of(Boolean busyLocal, String name, String trigger) {
        if (!Boolean.TRUE.equals(busyLocal)) return null;

        String trimmed = name == null || name.isBlank() ? null : name.trim();
        if (trimmed != null && trimmed.length() > MAX_NAME_LENGTH) trimmed = trimmed.substring(0, MAX_NAME_LENGTH);

        return new LocalRun(trimmed, RunTriggers.normalizeLocal(trigger));
    }
}
