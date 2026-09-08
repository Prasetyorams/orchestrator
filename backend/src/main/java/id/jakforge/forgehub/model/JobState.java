package id.jakforge.forgehub.model;

import java.util.Locale;

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
 */
public enum JobState {
    PENDING,
    RUNNING,
    SUCCESSFUL,
    FAULTED,
    STOPPING,
    STOPPED;

    /** Sudah berakhir; tidak akan berubah lagi sendiri. */
    public boolean selesai() {
        return this == SUCCESSFUL || this == FAULTED || this == STOPPED;
    }

    /**
     * Urai dari teks yang dikirim robot, atau null kalau tidak dikenal.
     *
     * <p>Mengembalikan null, bukan melempar: keadaan tak dikenal adalah
     * permintaan yang salah bentuk (400), bukan kerusakan server (500).
     */
    public static JobState dari(String teks) {
        if (teks == null || teks.isBlank()) return null;

        try {
            return valueOf(teks.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
