package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.repository.UserRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.JwtService;
import id.jakforge.forgehub.security.Passwords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Profil dan kata sandi milik pengguna yang sedang masuk.
 *
 * <p>Repositorinya palsu, bukan mock: yang diuji aturan di layanan, dan
 * repositori palsu cukup mencatat apa yang diminta tanpa menyeret agen
 * bytecode ke uji yang tidak membutuhkannya.
 */
class AuthServiceTest {

    private final ForgeHubPrincipal saya =
            new ForgeHubPrincipal(UUID.randomUUID(), UUID.randomUUID(), "FH_Admin", "Administrator");

    private final PenggunaPalsu pengguna = new PenggunaPalsu();

    private final AuthService layanan =
            new AuthService(pengguna, new JwtService("k".repeat(32), 60));

    // ---------- profil ----------

    @Test
    @DisplayName("profil tersimpan sesudah dipangkas, dan yang dikembalikan profil dari basis data")
    void profilTersimpan() {
        Map<String, Object> hasil = layanan.ubahProfil(saya,
                Permintaan.Profil.dari(Map.of("displayName", "  Pras  ", "email", " pras@contoh.id ")));

        assertArrayEquals(new String[] { "Pras", "pras@contoh.id" }, pengguna.profilTerakhir);
        assertEquals("FH_Admin", hasil.get("username"));
    }

    @Test
    @DisplayName("surel yang dikosongkan berarti dihapus")
    void surelKosongDihapus() {
        layanan.ubahProfil(saya, Permintaan.Profil.dari(Map.of("displayName", "Pras", "email", "")));

        assertEquals("Pras", pengguna.profilTerakhir[0]);
        assertNull(pengguna.profilTerakhir[1]);
    }

    @Test
    @DisplayName("nama tampilan kosong ditolak 400, dan tidak ada yang disimpan")
    void namaWajib() {
        assertEquals(HttpStatus.BAD_REQUEST, statusDari(() ->
                layanan.ubahProfil(saya, Permintaan.Profil.dari(Map.of("displayName", "   ")))));

        assertNull(pengguna.profilTerakhir);
    }

    @Test
    @DisplayName("nama tampilan melebihi kolomnya ditolak 400")
    void namaTerlaluPanjang() {
        assertEquals(HttpStatus.BAD_REQUEST, statusDari(() ->
                layanan.ubahProfil(saya, Permintaan.Profil.dari(Map.of("displayName", "x".repeat(201))))));
    }

    @Test
    @DisplayName("surel yang bentuknya salah ditolak 400")
    void surelSalahBentuk() {
        for (String salah : new String[] { "pras.contoh.id", "pras@contoh", "pras @contoh.id", "@contoh.id" }) {
            assertEquals(HttpStatus.BAD_REQUEST, statusDari(() ->
                    layanan.ubahProfil(saya, Permintaan.Profil.dari(
                            Map.of("displayName", "Pras", "email", salah)))), salah);
        }

        assertNull(pengguna.profilTerakhir);
    }

    @Test
    @DisplayName("pengguna yang sudah dihapus dijawab 401")
    void penggunaSudahTidakAda() {
        pengguna.barisBerubah = 0;

        assertEquals(HttpStatus.UNAUTHORIZED, statusDari(() ->
                layanan.ubahProfil(saya, Permintaan.Profil.dari(Map.of("displayName", "Pras")))));
    }

    // ---------- kata sandi ----------

    @Test
    @DisplayName("kata sandi lama yang salah dijawab 400, BUKAN 401 yang membuat klien keluar")
    void sandiLamaSalah() {
        pengguna.hash = Passwords.hash("sandi-lama-benar");

        assertEquals(HttpStatus.BAD_REQUEST, statusDari(() ->
                layanan.gantiSandi(saya, new Permintaan.GantiSandi("tebakan-salah", "sandi-baru-123"))));

        assertNull(pengguna.hashBaru);
    }

    @Test
    @DisplayName("kata sandi lama yang benar mengganti hash dengan sandi baru")
    void sandiLamaBenar() {
        pengguna.hash = Passwords.hash("sandi-lama-benar");

        layanan.gantiSandi(saya, new Permintaan.GantiSandi("sandi-lama-benar", "sandi-baru-123"));

        assertTrue(Passwords.verify("sandi-baru-123", pengguna.hashBaru));
    }

    @Test
    @DisplayName("kata sandi baru di bawah 8 karakter ditolak 400")
    void sandiBaruTerlaluPendek() {
        pengguna.hash = Passwords.hash("sandi-lama-benar");

        assertEquals(HttpStatus.BAD_REQUEST, statusDari(() ->
                layanan.gantiSandi(saya, new Permintaan.GantiSandi("sandi-lama-benar", "pendek"))));
    }

    // ---------- alat ----------

    private static HttpStatus statusDari(Executable aksi) {
        return assertThrows(ApiException.class, aksi).status();
    }

    /** Mencatat apa yang diminta layanan, tanpa basis data. */
    private static final class PenggunaPalsu extends UserRepository {

        String hash;
        String hashBaru;
        String[] profilTerakhir;
        int barisBerubah = 1;

        PenggunaPalsu() {
            super(null);
        }

        @Override
        public int ubahProfil(UUID userId, UUID tenantId, String namaTampil, String surel) {
            if (barisBerubah > 0) profilTerakhir = new String[] { namaTampil, surel };
            return barisBerubah;
        }

        @Override
        public Map<String, Object> profil(UUID userId, UUID tenantId) {
            return Map.of("username", "FH_Admin");
        }

        @Override
        public String hashSandi(UUID userId) {
            return hash;
        }

        @Override
        public void gantiSandi(UUID userId, String hash) {
            hashBaru = hash;
        }
    }
}
