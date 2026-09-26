package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.repository.Tempat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Folder mana yang dimaksud permintaan yang menyebut proses (atau pemicu)
 * lewat NAMA — {@link CatalogService#pilihFolder}.
 *
 * <p>Nama unik per folder sejak V5, jadi satu nama bisa ada di beberapa
 * folder. Studio (Start Job) tidak menyebut folder sama sekali.
 */
class PilihFolderTest {

    private static final String GANDA = "Proses 'Tagihan' ada di beberapa folder. Sebutkan foldernya.";

    private final UUID shared = UUID.randomUUID();
    private final UUID keuangan = UUID.randomUUID();
    private final UUID gudang = UUID.randomUUID();

    private UUID pilih(List<Tempat> tempat, UUID folder) {
        return CatalogService.pilihFolder(tempat, folder, GANDA);
    }

    @Test
    @DisplayName("Menyebut folder: yang di folder itu, bukan yang di folder bawaan")
    void menyebutFolder() {
        List<Tempat> tempat = List.of(new Tempat(shared, true), new Tempat(keuangan, false));

        assertEquals(keuangan, pilih(tempat, keuangan));
        assertEquals(shared, pilih(tempat, shared));
    }

    @Test
    @DisplayName("Menyebut folder yang tidak punya proses itu: tidak ada, tidak jatuh ke folder lain")
    void menyebutFolderLain() {
        assertNull(pilih(List.of(new Tempat(shared, true)), keuangan));
    }

    @Test
    @DisplayName("Tanpa folder dan tanpa proses: tidak ada")
    void tidakAda() {
        assertNull(pilih(List.of(), null));
    }

    @Test
    @DisplayName("Tanpa folder, satu-satunya proses bernama itu — di folder mana pun")
    void satuSatunya() {
        assertEquals(gudang, pilih(List.of(new Tempat(gudang, false)), null));
    }

    @Test
    @DisplayName("Tanpa folder, beberapa proses: yang di folder bawaan")
    void beberapaDenganBawaan() {
        // Urutan dari repositori menaruh folder bawaan lebih dulu, tapi
        // aturannya tidak boleh bergantung pada itu.
        List<Tempat> tempat = List.of(new Tempat(keuangan, false), new Tempat(shared, true));

        assertEquals(shared, pilih(tempat, null));
    }

    @Test
    @DisplayName("Tanpa folder, beberapa proses, tidak satu pun di folder bawaan: ditolak 409")
    void beberapaTanpaBawaan() {
        List<Tempat> tempat = List.of(new Tempat(keuangan, false), new Tempat(gudang, false));

        ApiException galat = assertThrows(ApiException.class, () -> pilih(tempat, null));

        assertEquals(HttpStatus.CONFLICT, galat.status());
        assertEquals(GANDA, galat.getMessage());
    }
}
