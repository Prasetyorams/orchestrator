package id.jakforge.forgehub.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Membuktikan bahwa kriptografi di sini menghasilkan hal yang SAMA PERSIS
 * dengan ForgeHub .NET.
 *
 * <p>Ini bukan uji kelengkapan, melainkan uji kecocokan. Basis data yang akan
 * dipindahkan sudah berisi hash kata sandi dan kredensial tersandi yang dibuat
 * oleh sisi .NET; kalau penurunan kuncinya berbeda satu bita saja, yang terjadi
 * bukan pesan kesalahan melainkan diam — pengguna tidak bisa masuk dengan kata
 * sandi yang benar, dan robot memakai kata sandi kosong ke aplikasi yang
 * diotomasi.
 *
 * <p>Semua nilai harapan di bawah ini DIHASILKAN oleh .NET, bukan oleh Java,
 * lalu disalin ke sini. Nilai yang dihasilkan sendiri oleh kode yang diuji
 * hanya membuktikan kode itu konsisten dengan dirinya sendiri.
 *
 * <p>Vektor PBKDF2-nya diambil dari baris pengguna yang benar-benar ada di
 * {@code forgehub.db}, bukan dibuat untuk keperluan uji.
 */
class DotNetCompatTest {

    /** Hash yang benar-benar tersimpan di forgehub.db untuk pengguna FH_Admin. */
    private static final String STORED_ADMIN_HASH =
            "pbkdf2-sha256$210000$Mjg3kQbkw5Wg1C0DQYt3dQ==$WY+wozz0fkTo7JpXzrIFVLrX+D4jLxNdo2Ex9TAytrc=";

    /** Master key tetap; hanya untuk vektor rujukan, tidak pernah dipakai sungguhan. */
    private static final String MASTER_B64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** Hasil Protect() .NET atas "sandi-rahasia-123" dengan nonce 01..0C. */
    private static final String PACKED_B64 =
            "AQIDBAUGBwgJCgsMVeaQ8gI0GbLsBq5RulaqgAsZyrnnrQoo5j5uBhmNBBHg";

    private static final String PLAINTEXT = "sandi-rahasia-123";

    // -----------------------------------------------------------------
    // Kata sandi
    // -----------------------------------------------------------------

    @Test
    @DisplayName("kata sandi asli dari forgehub.db diterima")
    void verifiesHashWrittenByDotNet() {
        assertTrue(Passwords.verify("forgehub", STORED_ADMIN_HASH),
                "Hash PBKDF2 buatan .NET harus bisa diverifikasi di sini. "
                        + "Kalau gagal, tidak ada yang bisa masuk setelah pindah.");
    }

    @Test
    @DisplayName("kata sandi yang salah ditolak")
    void rejectsWrongPassword() {
        assertFalse(Passwords.verify("forgehub ", STORED_ADMIN_HASH));
        assertFalse(Passwords.verify("Forgehub", STORED_ADMIN_HASH));
        assertFalse(Passwords.verify("", STORED_ADMIN_HASH));
        assertFalse(Passwords.verify(null, STORED_ADMIN_HASH));
    }

    @Test
    @DisplayName("hash yang cacat ditolak, bukan melempar")
    void rejectsMalformedHash() {
        assertFalse(Passwords.verify("forgehub", null));
        assertFalse(Passwords.verify("forgehub", ""));
        assertFalse(Passwords.verify("forgehub", "bcrypt$10$abc$def"));
        assertFalse(Passwords.verify("forgehub", "pbkdf2-sha256$210000$bukan-base64!$x"));
        assertFalse(Passwords.verify("forgehub", "pbkdf2-sha256$210000$Mjg3kQbkw5Wg1C0DQYt3dQ=="));
    }

    @Test
    @DisplayName("hash baru bisa diverifikasi dan memakai putaran mutakhir")
    void roundTripsOwnHash() {
        String hash = Passwords.hash("kata sandi apa saja");

        assertTrue(hash.startsWith("pbkdf2-sha256$210000$"),
                "Bentuknya harus sama dengan yang ditulis .NET: " + hash);
        assertTrue(Passwords.verify("kata sandi apa saja", hash));
        assertFalse(Passwords.needsRehash(hash));

        // Garam acak: dua hash dari kata sandi yang sama tidak boleh sama.
        assertNotEquals(hash, Passwords.hash("kata sandi apa saja"));
    }

    @Test
    @DisplayName("hash lama ditandai perlu di-hash ulang")
    void flagsWeakerHashForRehash() {
        assertTrue(Passwords.needsRehash("pbkdf2-sha256$1000$Mjg3kQbkw5Wg1C0DQYt3dQ==$WY+w"));
        assertEquals(210_000, Passwords.iterationsOf(STORED_ADMIN_HASH));
    }

    // -----------------------------------------------------------------
    // Kredensial
    // -----------------------------------------------------------------

    @Test
    @DisplayName("kredensial yang disandikan .NET bisa dibuka di sini")
    void unprotectsValueSealedByDotNet(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        SecretBox box = boxWithMasterKey(dir);

        assertEquals(PLAINTEXT, box.unprotect(PACKED_B64),
                "Paket AES-GCM buatan .NET harus terbuka di sini. Kalau gagal, "
                        + "penurunan kunci HKDF atau susunan nonce|tag|ciphertext berbeda.");
    }

    @Test
    @DisplayName("nilai yang disandikan di sini bisa dibuka lagi")
    void roundTripsOwnSecret(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        SecretBox box = boxWithMasterKey(dir);

        String packed = box.protect(PLAINTEXT);

        assertNotEquals(PACKED_B64, packed, "Nonce harus acak, bukan tetap.");
        assertEquals(PLAINTEXT, box.unprotect(packed));
    }

    @Test
    @DisplayName("paket yang rusak menghasilkan null, bukan pengecualian")
    void returnsNullForDamagedInput(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        SecretBox box = boxWithMasterKey(dir);

        assertEquals(null, box.unprotect("bukan base64 sama sekali !!!"));
        assertEquals(null, box.unprotect("QUJD"));                 // terlalu pendek
        assertEquals(null, box.protect(null));
        assertEquals(null, box.protect(""));

        // Satu bita diubah: tag GCM harus menolaknya.
        StringBuilder rusak = new StringBuilder(PACKED_B64);
        rusak.setCharAt(40, rusak.charAt(40) == 'A' ? 'B' : 'A');
        assertEquals(null, box.unprotect(rusak.toString()));
    }

    @Test
    @DisplayName("kunci lain tidak bisa membuka paket yang sama")
    void differentKeyCannotOpen(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path lain = dir.resolve("lain.key");
        Files.writeString(lain, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");

        assertEquals(null, new SecretBox(lain.toString()).unprotect(PACKED_B64),
                "Kalau ini berhasil, kuncinya tidak benar-benar dipakai.");
    }

    private static SecretBox boxWithMasterKey(Path dir) throws IOException {
        Path key = dir.resolve("signing.key");
        Files.writeString(key, MASTER_B64);

        return new SecretBox(key.toString());
    }
}
