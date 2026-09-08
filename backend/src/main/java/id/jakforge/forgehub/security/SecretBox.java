package id.jakforge.forgehub.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Penyandian nilai yang harus bisa dibaca kembali: kata sandi kredensial dan
 * aset bertipe rahasia.
 *
 * <p>Berbeda dari kata sandi pengguna, yang ini TIDAK boleh sekadar diringkas —
 * robot perlu nilai aslinya untuk masuk ke aplikasi yang diotomasi. Jadi dipakai
 * AES-GCM: satu kunci, dan setiap nilai punya nonce sendiri.
 *
 * <p><b>Bentuknya sama persis dengan ForgeHub .NET</b>, dan itu bukan
 * kemewahan. Kredensial yang sudah tersimpan di {@code forgehub.db} disandikan
 * dengan skema itu; kalau di sini beda satu langkah saja, hasilnya bukan pesan
 * kesalahan melainkan nilai {@code null} yang diam — robot mencoba masuk dengan
 * kata sandi kosong, dan yang terlihat hanyalah "login gagal" di aplikasi yang
 * diotomasi.
 *
 * <p>Yang harus sama persis:
 * <ul>
 *   <li>kunci diturunkan dari {@code signing.key} lewat HKDF-SHA256,</li>
 *   <li>tanpa garam — RFC 5869 mengartikannya sebagai garam berisi 32 bita nol,
 *       dan itulah yang dilakukan {@code HKDF.DeriveKey} di .NET saat garamnya
 *       tidak diberikan,</li>
 *   <li>label {@code forgehub-secret-box-v1},</li>
 *   <li>susunan paket {@code nonce | tag | ciphertext} dalam satu untai base64.
 *       Java menaruh tag di BELAKANG ciphertext, .NET memisahkannya, jadi
 *       urutannya disusun ulang di sini — bukan di pemanggilnya.</li>
 * </ul>
 */
@Component
public class SecretBox {

    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String HMAC = "HmacSHA256";

    private static final int NONCE_BYTES = 12;   // ukuran baku AES-GCM
    private static final int TAG_BYTES = 16;
    private static final int TAG_BITS = TAG_BYTES * 8;
    private static final int KEY_BYTES = 32;
    private static final int HASH_BYTES = 32;    // panjang keluaran SHA-256

    private static final byte[] INFO =
            "forgehub-secret-box-v1".getBytes(StandardCharsets.US_ASCII);

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    /**
     * @param keyFile berkas {@code signing.key}. Untuk memindahkan kredensial
     *                yang sudah ada, ini HARUS berkas yang sama dengan milik
     *                ForgeHub .NET — di Docker berarti dipasang sebagai volume,
     *                bukan dibuat baru.
     */
    public SecretBox(@Value("${forgehub.secret.key-file}") String keyFile) throws IOException {
        Path path = Path.of(keyFile);
        byte[] master = readOrCreate(path);

        this.key = new SecretKeySpec(hkdf(master, INFO, KEY_BYTES), "AES");
    }

    public String protect(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));

            // Java mengembalikan ciphertext diikuti tag; .NET menyimpannya
            // terpisah dengan tag di depan.
            byte[] sealed = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            int cipherLength = sealed.length - TAG_BYTES;

            byte[] packed = new byte[NONCE_BYTES + TAG_BYTES + cipherLength];
            System.arraycopy(nonce, 0, packed, 0, NONCE_BYTES);
            System.arraycopy(sealed, cipherLength, packed, NONCE_BYTES, TAG_BYTES);
            System.arraycopy(sealed, 0, packed, NONCE_BYTES + TAG_BYTES, cipherLength);

            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            // Menyimpan nilai polos karena penyandiannya gagal jauh lebih buruk
            // daripada gagal menyimpan sama sekali.
            throw new IllegalStateException("Penyandian rahasia gagal.", e);
        }
    }

    public String unprotect(String packedBase64) {
        if (packedBase64 == null || packedBase64.isEmpty()) {
            return null;
        }

        try {
            byte[] packed = Base64.getDecoder().decode(packedBase64);
            if (packed.length < NONCE_BYTES + TAG_BYTES) {
                return null;
            }

            int cipherLength = packed.length - NONCE_BYTES - TAG_BYTES;

            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(packed, 0, nonce, 0, NONCE_BYTES);

            // Disusun ulang menjadi urutan yang diharapkan Java.
            byte[] sealed = new byte[cipherLength + TAG_BYTES];
            System.arraycopy(packed, NONCE_BYTES + TAG_BYTES, sealed, 0, cipherLength);
            System.arraycopy(packed, NONCE_BYTES, sealed, cipherLength, TAG_BYTES);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));

            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Nilai yang rusak atau disandikan dengan kunci lain dianggap tidak
            // ada — sama seperti di .NET. Tapi dicatat, karena seluruh kredensial
            // yang tiba-tiba kosong biasanya berarti signing.key yang salah
            // dipasang, dan itu pertanyaan pertama yang akan muncul.
            log.warn("Rahasia tidak bisa dibuka; kemungkinan signing.key berbeda.");
            return null;
        }
    }

    /**
     * HKDF-SHA256 sesuai RFC 5869, tanpa garam.
     *
     * <p>Ditulis tangan, bukan menarik pustaka: seluruhnya dua panggilan HMAC,
     * dan HMAC sudah ada di setiap JRE. Menambah ketergantungan untuk dua puluh
     * baris bukan pertukaran yang masuk akal.
     *
     * <p>Karena panjang yang diminta (32) sama dengan panjang keluaran SHA-256,
     * tahap perluasannya cukup satu putaran. Kode ini tetap ditulis berputar
     * supaya permintaan yang lebih panjang tidak diam-diam menghasilkan kunci
     * yang terpotong.
     */
    private static byte[] hkdf(byte[] ikm, byte[] info, int lengthBytes) {
        try {
            // Ekstraksi. Garam kosong berarti 32 bita nol — itu yang ditentukan
            // RFC 5869 dan yang dilakukan HKDF.DeriveKey di .NET saat garamnya
            // tidak diberikan. Memakai kunci HMAC sepanjang nol bita akan
            // menghasilkan PRK yang sama sekali lain.
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(new byte[HASH_BYTES], HMAC));
            byte[] prk = mac.doFinal(ikm);

            // Perluasan.
            byte[] okm = new byte[lengthBytes];
            byte[] previous = new byte[0];
            int done = 0;

            for (int counter = 1; done < lengthBytes; counter++) {
                mac.init(new SecretKeySpec(prk, HMAC));
                mac.update(previous);
                mac.update(info);
                mac.update((byte) counter);

                previous = mac.doFinal();

                int take = Math.min(previous.length, lengthBytes - done);
                System.arraycopy(previous, 0, okm, done, take);
                done += take;
            }

            return okm;
        } catch (Exception e) {
            throw new IllegalStateException("HKDF gagal: " + HMAC + " tidak tersedia.", e);
        }
    }

    /**
     * Baca signing.key, atau buat kalau belum ada.
     *
     * <p>Membuat yang baru hanya benar untuk pemasangan BARU. Kalau berkasnya
     * hilang pada pemasangan yang sudah berisi data, kunci baru berarti seluruh
     * kredensial lama tidak bisa dibuka lagi — jadi kejadiannya dicatat dengan
     * jelas, bukan dilewati diam-diam.
     */
    private static byte[] readOrCreate(Path path) throws IOException {
        if (Files.exists(path)) {
            return Base64.getDecoder().decode(Files.readString(path).trim());
        }

        log.warn("signing.key tidak ditemukan di {} — dibuat yang baru. "
                + "Kredensial yang disandikan dengan kunci lain TIDAK akan terbaca.", path);

        byte[] master = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(master);

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(path, Base64.getEncoder().encodeToString(master));

        return master;
    }
}
