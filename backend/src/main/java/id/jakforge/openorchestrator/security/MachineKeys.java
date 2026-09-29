package id.jakforge.openorchestrator.security;

import id.jakforge.openorchestrator.common.Hashes;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Machine key: identitas Robot Agent di sebuah mesin.
 *
 * <p>Bentuknya {@code oo_mk_} + 256 bit acak dalam base64url. Awalan itu ada
 * supaya kunci yang tercecer — di log, di tiket, di tangkapan layar — mudah
 * dikenali dan disensor, dan supaya orang tahu apa yang sedang dipegangnya.
 *
 * <p>Orchestrator hanya menyimpan HASH-nya (SHA-256). Kunci dengan 256 bit
 * acak tidak perlu diperlambat seperti kata sandi: menebaknya tidak mungkin,
 * dan hash yang cepat membuat pencariannya cukup satu indeks.
 */
public final class MachineKeys {

    public static final String PREFIX = "oo_mk_";

    /** Berapa karakter awal yang ditampilkan di dasbor untuk mengenali kunci mana yang terpasang. */
    static final int DISPLAY_PREFIX_LENGTH = PREFIX.length() + 4;

    private static final int KEY_BYTES = 32;
    private static final int MIN_KEY_LENGTH = PREFIX.length() + 40;
    private static final int MAX_KEY_LENGTH = 100;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** @param key ditampilkan SEKALI; setelah itu hanya {@code hash} yang tersimpan */
    public record Generated(String key, String hash, String displayPrefix) {
    }

    private MachineKeys() {
    }

    public static Generated generate() {
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);

        String key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        return new Generated(key, hash(key), key.substring(0, DISPLAY_PREFIX_LENGTH));
    }

    public static String hash(String key) {
        return Hashes.sha256Hex(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Penanda kunci di dalam token agent: awal hash-nya. Kunci yang diganti atau
     * dicabut punya penanda lain (atau tidak ada), dan token yang dibuat dengan
     * kunci lama langsung ditolak — tidak menunggu token itu kedaluwarsa.
     */
    public static String keyIdOf(String keyHash) {
        return keyHash == null ? null : keyHash.substring(0, Math.min(16, keyHash.length()));
    }

    /** Bentuknya masuk akal — memeriksa ini lebih dulu menghemat kueri untuk sampah. */
    public static boolean looksValid(String text) {
        return text != null
                && text.startsWith(PREFIX)
                && text.length() >= MIN_KEY_LENGTH
                && text.length() <= MAX_KEY_LENGTH;
    }
}
