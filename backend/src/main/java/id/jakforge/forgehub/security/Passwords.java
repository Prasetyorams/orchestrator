package id.jakforge.forgehub.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Penyimpanan kata sandi pengguna.
 *
 * <p>PBKDF2-SHA256 dengan 210.000 putaran — angka yang direkomendasikan OWASP
 * untuk PBKDF2-HMAC-SHA256.
 *
 * <p>BUKAN BCrypt, dan itu bukan pilihan gaya. Berkas {@code forgehub.db} yang
 * dipakai hari ini berisi hash PBKDF2 yang dibuat oleh ForgeHub .NET, dan kata
 * sandi yang sudah dipakai orang harus tetap bisa dipakai sesudah pindah ke
 * PostgreSQL. Verifikator BCrypt tidak akan pernah cocok dengan hash itu; yang
 * terjadi bukan pesan kesalahan yang jelas, melainkan "kata sandi salah" untuk
 * kata sandi yang benar.
 *
 * <p>Bentuk yang disimpan sama persis dengan sisi .NET:
 * <pre>pbkdf2-sha256$210000$&lt;garam base64&gt;$&lt;hash base64&gt;</pre>
 *
 * <p>Perbandingannya memakai {@link MessageDigest#isEqual} yang berwaktu tetap.
 * Perbandingan biasa berhenti pada bita pertama yang berbeda, dan selisih
 * waktunya — walau kecil — cukup untuk menebak nilai yang benar bita demi bita.
 */
public final class Passwords {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";

    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);

        byte[] hash = derive(password, salt, ITERATIONS, HASH_BYTES);

        return PREFIX + "$" + ITERATIONS
                + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean verify(String password, String stored) {
        if (stored == null || stored.isEmpty()) {
            return false;
        }

        // Batas -1 dipakai supaya bagian kosong di akhir tidak dibuang diam-diam:
        // hash yang terpotong harus GAGAL diurai, bukan berubah menjadi bentuk
        // lain yang kebetulan sah.
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }

        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }

        if (iterations <= 0 || expected.length == 0) {
            return false;
        }

        byte[] actual = derive(password, salt, iterations, expected.length);

        return MessageDigest.isEqual(actual, expected);
    }

    /**
     * Jumlah putaran yang tersimpan di sebuah hash.
     *
     * <p>Dipakai untuk memutuskan apakah kata sandi perlu di-hash ulang saat
     * pengguna berhasil masuk: hash lama dengan putaran lebih sedikit tetap sah,
     * tapi tidak boleh dibiarkan begitu selamanya.
     */
    public static int iterationsOf(String stored) {
        if (stored == null) {
            return 0;
        }

        String[] parts = stored.split("\\$", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return 0;
        }

        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean needsRehash(String stored) {
        return iterationsOf(stored) < ITERATIONS;
    }

    private static byte[] derive(String password, byte[] salt, int iterations, int lengthBytes) {
        // Panjang diminta dalam BIT, bukan bita. Melewatkan 32 di sini akan
        // menghasilkan kunci 4 bita yang tetap "berhasil" tanpa keluhan apa pun.
        PBEKeySpec spec = new PBEKeySpec(
                password == null ? new char[0] : password.toCharArray(),
                salt, iterations, lengthBytes * 8);

        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            // Algoritmanya wajib ada di setiap JRE. Kalau sampai tidak ada, tidak
            // ada jalan aman untuk melanjutkan: memakai algoritma cadangan
            // diam-diam berarti hash yang tersimpan tidak lagi bisa diverifikasi.
            throw new IllegalStateException("PBKDF2 tidak tersedia: " + ALGORITHM, e);
        } finally {
            spec.clearPassword();
        }
    }
}
