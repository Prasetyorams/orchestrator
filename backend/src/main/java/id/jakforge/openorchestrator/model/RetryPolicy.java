package id.jakforge.openorchestrator.model;

/**
 * Kapan job yang gagal diulang otomatis.
 *
 * <p>HANYA job yang belum pernah menjalankan workflow-nya (belum pernah
 * RUNNING), dan hanya untuk kegagalan infrastruktur — sesi tidak siap,
 * Executor tidak menyala, paket gagal diunduh, agent hilang sebelum mulai.
 * Workflow yang sudah berjalan bisa saja sudah mengirim email atau mengisi
 * data sebelum gagal; mengulangnya otomatis berarti mengerjakan hal yang sama
 * dua kali. Itu diulang manual oleh orang yang tahu akibatnya.
 *
 * <p>Percobaan ulang selalu job BARU ({@code retry_of} menunjuk yang lama),
 * supaya riwayat job yang gagal tetap jujur.
 */
public final class RetryPolicy {

    /** Batas atas setelan {@code processes.max_retries}. */
    public static final int MAX_RETRIES_LIMIT = 2;

    private RetryPolicy() {
    }

    /**
     * @param attempt    percobaan ke berapa job yang gagal ini (mulai 1)
     * @param maxRetries berapa kali boleh diulang menurut setelan prosesnya
     */
    public static boolean shouldRetry(String errorCode, boolean hasRun, int attempt, int maxRetries,
                                      boolean alreadyRetried) {
        return !alreadyRetried
                && !hasRun
                && errorCode != null
                && AgentErrorCodes.RETRYABLE.contains(errorCode)
                && attempt <= Math.min(maxRetries, MAX_RETRIES_LIMIT);
    }
}
