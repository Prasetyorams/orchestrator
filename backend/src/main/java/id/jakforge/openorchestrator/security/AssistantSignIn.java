package id.jakforge.openorchestrator.security;

import id.jakforge.openorchestrator.common.Hashes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Bahan masuk Open Assistant lewat dasbor: OAuth 2.0 untuk aplikasi desktop
 * (RFC 8252) dengan PKCE S256 (RFC 7636). Fungsi murni, tanpa basis data.
 *
 * <p>Klien dan alamat kembalinya DAFTAR PUTIH satu nilai, bukan apa pun yang
 * dikirim peramban: kode yang dikirim ke alamat pilihan penyerang sama dengan
 * menyerahkan sambungan ke penyerang. PKCE menutup sisanya — kode yang
 * tersadap tidak berguna tanpa code_verifier yang hanya ada di memori Open
 * Assistant yang memulai alurnya.
 */
public final class AssistantSignIn {

    public static final String CLIENT = "open-assistant";
    public static final String REDIRECT_URI = "openassistant://signin";
    public static final String CHALLENGE_METHOD = "S256";

    /** BASE64URL(SHA-256(...)) tanpa padding selalu 43 karakter. */
    private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    /** RFC 7636 4.1: 43–128 karakter "unreserved". */
    private static final Pattern VERIFIER = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");

    /** Dikembalikan apa adanya; dibatasi supaya tidak bisa membawa apa pun selain penanda acak. */
    private static final Pattern STATE = Pattern.compile("^[A-Za-z0-9._~=-]{8,512}$");

    /** Bagian nama robot: huruf, angka, titik, garis bawah, tanda hubung. */
    private static final Pattern NOT_NAME_CHARACTER = Pattern.compile("[^A-Za-z0-9._-]+");

    private static final int SECRET_BYTES = 32;
    private static final int NAME_PART_LENGTH = 60;

    private static final SecureRandom RANDOM = new SecureRandom();

    private AssistantSignIn() {
    }

    /** Kode atau refresh token: 256 bit acak dalam base64url. */
    public static String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Yang disimpan untuk kode dan refresh token; yang aslinya tidak pernah. */
    public static String hash(String secret) {
        return Hashes.sha256Hex(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code BASE64URL(SHA256(ASCII(code_verifier)))}. */
    public static String challengeOf(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 tidak tersedia", e);
        }
    }

    /** Dibandingkan dalam waktu tetap: lama pemeriksaan tidak boleh membocorkan berapa karakter yang cocok. */
    public static boolean verifierMatches(String verifier, String challenge) {
        if (!isVerifier(verifier) || !isChallenge(challenge)) return false;

        return MessageDigest.isEqual(challengeOf(verifier).getBytes(StandardCharsets.US_ASCII),
                challenge.getBytes(StandardCharsets.US_ASCII));
    }

    public static boolean isChallenge(String text) {
        return text != null && CHALLENGE.matcher(text).matches();
    }

    public static boolean isVerifier(String text) {
        return text != null && VERIFIER.matcher(text).matches();
    }

    public static boolean isState(String text) {
        return text != null && STATE.matcher(text).matches();
    }

    /** Tautan yang dibuka peramban sesudah orangnya menyetujui. */
    public static String redirect(String code, String state) {
        return REDIRECT_URI + "?code=" + encode(code) + "&state=" + encode(state);
    }

    /**
     * Nama robot attended untuk pengguna di sebuah komputer: "fajar-DESKTOP-01".
     *
     * <p>Satu robot per pengguna DAN mesin, bukan per pengguna: robot yang
     * berdenyut dari dua komputer sekaligus berganti-ganti mesin setiap
     * denyut, dan job untuknya bisa diambil komputer yang salah.
     */
    public static String robotNameFor(String username, String machineName) {
        return namePart(username, "pengguna") + "-" + namePart(machineName, "pc");
    }

    static String namePart(String text, String fallback) {
        String cleaned = NOT_NAME_CHARACTER.matcher(text == null ? "" : text.trim()).replaceAll("-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[-.]+|[-.]+$", "");

        if (cleaned.isEmpty()) cleaned = fallback;
        return cleaned.length() > NAME_PART_LENGTH ? cleaned.substring(0, NAME_PART_LENGTH) : cleaned;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
