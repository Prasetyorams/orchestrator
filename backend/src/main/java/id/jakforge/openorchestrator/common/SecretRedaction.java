package id.jakforge.openorchestrator.common;

import java.util.regex.Pattern;

/**
 * Menyensor token dan machine key yang tercecer di pesan catatan.
 *
 * <p>Jaring pengaman, bukan izin: robot WAJIB tidak menulis rahasia ke log.
 * Tapi catatan dibaca jauh lebih banyak orang daripada yang boleh memegang
 * token robot, dan satu baris "request gagal: Authorization: Bearer eyJ..."
 * cukup untuk menyamar sebagai robot itu sampai tokennya kedaluwarsa.
 *
 * <p>Yang dikenali hanya bentuk yang jelas: JWT (tiga bagian base64url,
 * diawali "eyJ") dan machine key ("oo_mk_..."). Kata sandi bebas tidak
 * punya bentuk yang bisa dikenali, jadi tidak ada upaya menebaknya.
 */
public final class SecretRedaction {

    static final String TOKEN_REPLACEMENT = "[token disensor]";
    static final String KEY_REPLACEMENT = "[kunci disensor]";

    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern MACHINE_KEY = Pattern.compile("oo_mk_[A-Za-z0-9_-]{20,}");

    private SecretRedaction() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text;

        String result = text;
        if (result.contains("eyJ")) result = JWT.matcher(result).replaceAll(TOKEN_REPLACEMENT);
        if (result.contains("oo_mk_")) result = MACHINE_KEY.matcher(result).replaceAll(KEY_REPLACEMENT);

        return result;
    }
}
