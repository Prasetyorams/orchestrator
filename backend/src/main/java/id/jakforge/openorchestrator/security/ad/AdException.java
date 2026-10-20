package id.jakforge.openorchestrator.security.ad;

/**
 * Masuk atau pencarian Windows AD yang gagal. {@link #failure()} untuk
 * memutuskan jawaban; pesannya hanya untuk catatan server — tidak pernah
 * diteruskan ke peramban apa adanya, karena bisa menyebut server, DN, atau
 * kode galat direktori.
 */
public class AdException extends RuntimeException {

    public enum Failure {
        /** Nama atau sandi salah — termasuk akun yang tidak ada (AD: 52e, 525). */
        INVALID_CREDENTIALS,
        /** AD: 533. */
        ACCOUNT_DISABLED,
        /** AD: 775. */
        ACCOUNT_LOCKED,
        /** AD: 701. */
        ACCOUNT_EXPIRED,
        /** AD: 532. */
        PASSWORD_EXPIRED,
        /** AD: 773. */
        PASSWORD_MUST_CHANGE,
        /** AD: 530, 531 — di luar jam atau dari mesin yang tidak diizinkan. */
        LOGON_RESTRICTED,
        /** Domain controller tidak terjangkau, atau tidak menjawab dalam batas waktu. */
        UNREACHABLE,
        /** Sambungan TLS ditolak: sertifikat tidak dipercaya atau nama server tidak cocok. */
        TLS,
        /** Pencarian akun butuh akun layanan (AD_BIND_DN), dan tidak ada. */
        LOOKUP_UNAVAILABLE,
        /** Kesalahan direktori lain, termasuk akun layanan yang ditolak. */
        DIRECTORY_ERROR
    }

    private final Failure failure;

    public AdException(Failure failure, String detail) {
        super(detail);
        this.failure = failure;
    }

    public AdException(Failure failure, String detail, Throwable cause) {
        super(detail, cause);
        this.failure = failure;
    }

    public Failure failure() {
        return failure;
    }

    /** Kegagalan karena akun atau sandinya — bukan karena direktori atau jaringan. */
    public boolean isCredentialProblem() {
        return switch (failure) {
            case INVALID_CREDENTIALS, ACCOUNT_DISABLED, ACCOUNT_LOCKED, ACCOUNT_EXPIRED, PASSWORD_EXPIRED,
                 PASSWORD_MUST_CHANGE, LOGON_RESTRICTED -> true;
            default -> false;
        };
    }
}
