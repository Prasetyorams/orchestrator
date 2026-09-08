package id.jakforge.forgehub.service;

/**
 * Batas jumlah baris yang boleh diminta.
 *
 * <p>Satu tempat untuk aturan yang sama di sembilan endpoint. Tanpa batas atas,
 * satu permintaan {@code ?limit=999999999} membaca seluruh tabel log ke dalam
 * memori dan menjatuhkan layanannya — dan itu tidak perlu niat jahat, cukup
 * satu salah ketik.
 */
public final class Batas {

    private Batas() {
    }

    public static int antara(Integer diminta, int bawaan, int maksimum) {
        if (diminta == null) return bawaan;

        return Math.max(1, Math.min(diminta, maksimum));
    }
}
