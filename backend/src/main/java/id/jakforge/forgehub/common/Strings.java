package id.jakforge.forgehub.common;

/**
 * Penormal teks untuk badan permintaan bertipe.
 *
 * <p>Aturannya sama dengan {@link RequestBodies}, supaya endpoint yang memakai
 * record bertipe menerima isian persis seperti endpoint yang membaca badan
 * longgar: untai kosong berarti "tidak diisi", dan nama selalu dipangkas.
 */
public final class Strings {

    private Strings() {
    }

    /** Dipangkas; kosong atau hanya spasi menjadi null. */
    public static String trimToNull(String value) {
        if (value == null) return null;

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Apa adanya; untai kosong menjadi null. Spasi TIDAK dipangkas — kata sandi boleh berspasi. */
    public static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Nilai bawaan kalau kosong atau tidak diisi. */
    public static String defaultIfEmpty(String value, String defaultValue) {
        return value == null || value.isEmpty() ? defaultValue : value;
    }
}
