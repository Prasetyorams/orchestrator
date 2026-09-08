package id.jakforge.forgehub.model;

import java.util.Locale;

/** Keadaan satu butir antrean. */
public enum QueueItemStatus {
    NEW,
    IN_PROGRESS,
    SUCCESSFUL,
    FAILED,
    RETRIED,
    ABANDONED;

    /**
     * Keadaan yang boleh DILAPORKAN robot sebagai hasil.
     *
     * <p>NEW dan IN_PROGRESS tidak termasuk: keduanya ditetapkan ForgeHub saat
     * butirnya dibuat dan diambil, bukan dilaporkan dari luar. Robot yang bisa
     * mengembalikan butir ke NEW sendiri akan membuatnya diproses dua kali.
     */
    public boolean bolehDilaporkan() {
        return this == SUCCESSFUL || this == FAILED || this == RETRIED || this == ABANDONED;
    }

    public static QueueItemStatus dari(String teks) {
        if (teks == null || teks.isBlank()) return null;

        try {
            return valueOf(teks.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
