package id.jakforge.forgehub.security;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pola izin, perapian daftar izin dari layar Peran, dan izin seseorang. */
class IzinTest {

    // ---------- pola ----------

    @Test
    @DisplayName("*, sumber.*, *.tindakan, dan izin persis")
    void pola() {
        assertTrue(Izin.cocok(List.of("*"), "users.delete"));

        assertTrue(Izin.cocok(List.of("processes.*"), "processes.delete"));
        assertFalse(Izin.cocok(List.of("processes.*"), "jobs.read"));

        assertTrue(Izin.cocok(List.of("*.read"), "audit.read"));
        assertFalse(Izin.cocok(List.of("*.read"), "jobs.create"));

        assertTrue(Izin.cocok(List.of("jobs.read", "jobs.create"), "jobs.create"));
        assertFalse(Izin.cocok(List.of("jobs.read"), "jobs.update"));
        assertFalse(Izin.cocok(List.of(), "jobs.read"));
    }

    @Test
    @DisplayName("teks tersimpan: dipisah koma, dipangkas, yang kosong dibuang")
    void urai() {
        assertEquals(Set.of("a.read", "b.*", "*.read"), Izin.urai(" a.read, b.* ,,*.read "));
        assertTrue(Izin.urai(null).isEmpty());
        assertTrue(Izin.urai("").isEmpty());
    }

    // ---------- layar Peran ----------

    @Test
    @DisplayName("izin dari layar Peran dirapikan: urutan katalog, sumber penuh menjadi sumber.*")
    void rapikan() {
        List<String> hasil = Izin.rapikan(List.of(
                "jobs.read", "processes.delete", "processes.read", "processes.update", "processes.create",
                "jobs.read", " audit.read "));

        // audit hanya punya satu tindakan, jadi "audit.read" berarti seluruh sumbernya.
        assertEquals(List.of("processes.*", "jobs.read", "audit.*"), hasil);
    }

    @Test
    @DisplayName("sumber.* hanya untuk sumber yang SEMUA tindakannya dipilih")
    void rapikanPola() {
        assertEquals(List.of("alerts.read"), Izin.rapikan(List.of("alerts.read")));
        assertEquals(List.of("alerts.*"), Izin.rapikan(List.of("alerts.*")));
        assertEquals(List.of("logs.*"), Izin.rapikan(List.of("logs.read", "logs.create", "logs.delete")));
        assertEquals(List.of("logs.read", "logs.delete"), Izin.rapikan(List.of("logs.delete", "logs.read")));
    }

    @Test
    @DisplayName("izin yang tidak dikenal ditolak, bukan dibuang diam-diam")
    void tidakDikenal() {
        for (String salah : List.of("procesess.read", "audit.update", "processes", "*", "*.read", "processes.baca")) {
            ApiException e = assertThrows(ApiException.class, () -> Izin.rapikan(List.of(salah)), salah);
            assertEquals(HttpStatus.BAD_REQUEST, e.status(), salah);
        }
    }

    @Test
    @DisplayName("tidak ada yang bisa memberi izin yang ia sendiri tidak punya")
    void tercakup() {
        assertNull(Izin.takTercakup(List.of("*"), List.of("*")));
        assertNull(Izin.takTercakup(List.of("processes.*"), List.of("processes.read", "processes.delete")));
        assertNull(Izin.takTercakup(List.of("*.read"), List.of("jobs.read", "audit.read")));

        assertEquals("jobs.create", Izin.takTercakup(List.of("*.read"), List.of("jobs.read", "jobs.create")));

        // Administrator ("*") memuat izin pertama katalog yang tidak dimiliki.
        assertEquals("processes.create", Izin.takTercakup(List.of("processes.read"), List.of("*")));
    }

    // ---------- izin seseorang ----------

    private final UUID penyewa = UUID.randomUUID();

    private ForgeHubPrincipal orang(String peran) {
        return new ForgeHubPrincipal(UUID.randomUUID(), penyewa, "budi", peran);
    }

    @Test
    @DisplayName("Administrator selalu semuanya, apa pun isi barisnya")
    void administrator() {
        PenggunaPalsu tabel = new PenggunaPalsu("Administrator", true, "jobs.read");

        assertEquals(Set.of("*"), new Izin(tabel).pola(orang("Administrator")));
    }

    @Test
    @DisplayName("izin mengikuti peran SAAT INI di basis data, bukan peran di token")
    void peranSaatIni() {
        PenggunaPalsu tabel = new PenggunaPalsu("Auditor", true, "*.read");
        Izin izin = new Izin(tabel);

        // Tokennya masih menyebut Administrator; basis data sudah Auditor.
        ForgeHubPrincipal p = orang("Administrator");

        assertTrue(izin.boleh(p, "jobs.read"));
        assertFalse(izin.boleh(p, "jobs.create"));
    }

    @Test
    @DisplayName("pengguna nonaktif atau yang sudah dihapus tidak punya izin apa pun")
    void nonaktif() {
        assertTrue(new Izin(new PenggunaPalsu("Administrator", false, "*")).pola(orang("Administrator")).isEmpty());
        assertTrue(new Izin(new PenggunaPalsu(null, true, null)).pola(orang("Robot")).isEmpty());
    }

    @Test
    @DisplayName("diingat sebentar, dan dilupakan seketika sesudah peran berubah")
    void ingatan() {
        PenggunaPalsu tabel = new PenggunaPalsu("Robot", true, "jobs.read");
        Izin izin = new Izin(tabel);
        ForgeHubPrincipal p = orang("Robot");

        assertTrue(izin.boleh(p, "jobs.read"));
        assertTrue(izin.boleh(p, "jobs.read"));
        assertEquals(1, tabel.dibaca, "pemeriksaan kedua tidak menyentuh basis data");

        tabel.izin = "jobs.update";
        assertTrue(izin.boleh(p, "jobs.read"), "masih yang lama sampai dilupakan");

        izin.lupakan();

        assertFalse(izin.boleh(p, "jobs.read"));
        assertTrue(izin.boleh(p, "jobs.update"));
        assertEquals(2, tabel.dibaca);
    }

    @Test
    @DisplayName("perlu() menolak dengan 403 yang menyebut izinnya")
    void perlu() {
        Izin izin = new Izin(new PenggunaPalsu("Auditor", true, "*.read"));

        ApiException e = assertThrows(ApiException.class, () -> izin.perlu(orang("Auditor"), "assets.delete"));

        assertEquals(HttpStatus.FORBIDDEN, e.status());
        assertEquals("Peran Anda tidak punya izin 'assets.delete'.", e.getMessage());
    }

    /** Satu baris users ⨝ roles, tanpa basis data. */
    private static final class PenggunaPalsu extends UserRepository {

        final String peran;
        final boolean aktif;
        String izin;
        int dibaca;

        PenggunaPalsu(String peran, boolean aktif, String izin) {
            super(null);
            this.peran = peran;
            this.aktif = aktif;
            this.izin = izin;
        }

        @Override
        public Map<String, Object> izinPengguna(UUID userId, UUID tenantId) {
            dibaca++;
            if (peran == null) return null;

            Map<String, Object> baris = new HashMap<>();
            baris.put("role", peran);
            baris.put("isActive", aktif);
            baris.put("permissions", izin);

            return baris;
        }
    }
}
