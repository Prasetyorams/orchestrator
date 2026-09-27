package id.jakforge.forgehub.common;

/**
 * Batas jumlah baris yang boleh diminta.
 *
 * <p>Satu tempat untuk aturan yang sama di sembilan endpoint. Tanpa batas atas,
 * satu permintaan {@code ?limit=999999999} membaca seluruh tabel log ke dalam
 * memori dan menjatuhkan layanannya — dan itu tidak perlu niat jahat, cukup
 * satu salah ketik.
 */
public final class PageLimits {

    private PageLimits() {
    }

    /** Nilai bawaan kalau tidak diminta; selain itu dijepit ke 1..maksimum. */
    public static int clamp(Integer requested, int defaultLimit, int maxLimit) {
        if (requested == null) return defaultLimit;

        return Math.max(1, Math.min(requested, maxLimit));
    }
}
