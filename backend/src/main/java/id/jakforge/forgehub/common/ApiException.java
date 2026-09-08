package id.jakforge.forgehub.common;

import org.springframework.http.HttpStatus;

/**
 * Kesalahan yang membawa status HTTP-nya sendiri.
 *
 * <p>Ada supaya lapisan service tidak perlu tahu apa pun tentang HTTP. Service
 * yang mengembalikan {@code ResponseEntity} tidak bisa dipanggil dari penjadwal
 * atau dari service lain tanpa membawa serta seluruh gagasan permintaan dan
 * balasan — dan begitu itu terjadi, aturan bisnisnya tidak bisa lagi diuji
 * tanpa menyalakan Tomcat.
 *
 * <p>Pesannya DITAMPILKAN kepada pemakai, jadi isinya harus kalimat yang
 * menjelaskan apa yang salah, bukan potongan kueri atau nama kolom.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String pesan) {
        super(pesan);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    // -----------------------------------------------------------------
    // Bentuk yang paling sering dipakai
    // -----------------------------------------------------------------

    /** Permintaan yang salah bentuk atau melanggar aturan. */
    public static ApiException salah(String pesan) {
        return new ApiException(HttpStatus.BAD_REQUEST, pesan);
    }

    /** Tidak ada, atau bukan milik penyewa yang meminta — keduanya dijawab sama. */
    public static ApiException tidakAda(String pesan) {
        return new ApiException(HttpStatus.NOT_FOUND, pesan);
    }

    /** Sudah ada, dan tidak boleh digandakan. */
    public static ApiException sudahAda(String pesan) {
        return new ApiException(HttpStatus.CONFLICT, pesan);
    }

    /** Dikenali, tapi tidak berhak. Berbeda dari 401, yang berarti belum dikenali. */
    public static ApiException tidakBerhak(String pesan) {
        return new ApiException(HttpStatus.FORBIDDEN, pesan);
    }

    /** Belum dikenali, atau tokennya sudah tidak berlaku. */
    public static ApiException belumMasuk(String pesan) {
        return new ApiException(HttpStatus.UNAUTHORIZED, pesan);
    }
}
