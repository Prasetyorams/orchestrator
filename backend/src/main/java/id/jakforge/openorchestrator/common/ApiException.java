package id.jakforge.openorchestrator.common;

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
 *
 * <p>{@code errorCode} untuk mesin, bukan untuk orang: Robot Agent bercabang
 * berdasarkan kode itu, bukan berdasarkan teks pesan yang boleh berubah.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final String state;
    private final String minAgentVersion;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null, null, null);
    }

    private ApiException(HttpStatus status, String message, String errorCode, String state, String minAgentVersion) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.state = state;
        this.minAgentVersion = minAgentVersion;
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    /** Keadaan job saat ini, untuk 409 InvalidTransition. */
    public String state() {
        return state;
    }

    /** Versi agent minimal, untuk 426 AgentTooOld. */
    public String minAgentVersion() {
        return minAgentVersion;
    }

    /** Salinan dengan kode mesin. */
    public ApiException withCode(String code) {
        return new ApiException(status, getMessage(), code, state, minAgentVersion);
    }

    public ApiException withState(String currentState) {
        return new ApiException(status, getMessage(), errorCode, currentState, minAgentVersion);
    }

    public ApiException withMinAgentVersion(String version) {
        return new ApiException(status, getMessage(), errorCode, state, version);
    }

    // -----------------------------------------------------------------
    // Bentuk yang paling sering dipakai
    // -----------------------------------------------------------------

    /** Permintaan yang salah bentuk atau melanggar aturan. */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    /** Tidak ada, atau bukan milik penyewa yang meminta — keduanya dijawab sama. */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    /** Sudah ada, dan tidak boleh digandakan. */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /** Dikenali, tapi tidak berhak. Berbeda dari 401, yang berarti belum dikenali. */
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    /** Belum dikenali, atau tokennya sudah tidak berlaku. */
    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    /** Terlalu besar atau terlalu banyak. */
    public static ApiException tooLarge(String message) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, message);
    }
}
