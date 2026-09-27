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
    public boolean isReportable() {
        return this == SUCCESSFUL || this == FAILED || this == RETRIED || this == ABANDONED;
    }

    /** Null kalau tidak dikenal. */
    public static QueueItemStatus parse(String text) {
        if (text == null || text.isBlank()) return null;

        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
