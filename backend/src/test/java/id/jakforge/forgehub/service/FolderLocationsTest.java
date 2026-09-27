package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.repository.FolderLocation;
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
 * lewat NAMA — {@link FolderLocations#resolve}.
 *
 * <p>Nama unik per folder sejak V5, jadi satu nama bisa ada di beberapa
 * folder. Studio (Start Job) tidak menyebut folder sama sekali.
 */
class FolderLocationsTest {

    private static final String AMBIGUOUS_MESSAGE = "Proses 'Tagihan' ada di beberapa folder. Sebutkan foldernya.";

    private final UUID sharedFolder = UUID.randomUUID();
    private final UUID financeFolder = UUID.randomUUID();
    private final UUID warehouseFolder = UUID.randomUUID();

    private UUID resolve(List<FolderLocation> locations, UUID requestedFolder) {
        return FolderLocations.resolve(locations, requestedFolder, AMBIGUOUS_MESSAGE);
    }

    @Test
    @DisplayName("Menyebut folder: yang di folder itu, bukan yang di folder bawaan")
    void requestedFolderWins() {
        List<FolderLocation> locations = List.of(
                new FolderLocation(sharedFolder, true), new FolderLocation(financeFolder, false));

        assertEquals(financeFolder, resolve(locations, financeFolder));
        assertEquals(sharedFolder, resolve(locations, sharedFolder));
    }

    @Test
    @DisplayName("Menyebut folder yang tidak punya proses itu: tidak ada, tidak jatuh ke folder lain")
    void requestedFolderWithoutProcess() {
        assertNull(resolve(List.of(new FolderLocation(sharedFolder, true)), financeFolder));
    }

    @Test
    @DisplayName("Tanpa folder dan tanpa proses: tidak ada")
    void noFolderAndNoProcess() {
        assertNull(resolve(List.of(), null));
    }

    @Test
    @DisplayName("Tanpa folder, satu-satunya proses bernama itu — di folder mana pun")
    void onlyProcessWithThatName() {
        assertEquals(warehouseFolder, resolve(List.of(new FolderLocation(warehouseFolder, false)), null));
    }

    @Test
    @DisplayName("Tanpa folder, beberapa proses: yang di folder bawaan")
    void severalProcessesPrefersDefaultFolder() {
        // Urutan dari repositori menaruh folder bawaan lebih dulu, tapi
        // aturannya tidak boleh bergantung pada itu.
        List<FolderLocation> locations = List.of(
                new FolderLocation(financeFolder, false), new FolderLocation(sharedFolder, true));

        assertEquals(sharedFolder, resolve(locations, null));
    }

    @Test
    @DisplayName("Tanpa folder, beberapa proses, tidak satu pun di folder bawaan: ditolak 409")
    void severalProcessesWithoutDefaultFolderIsAmbiguous() {
        List<FolderLocation> locations = List.of(
                new FolderLocation(financeFolder, false), new FolderLocation(warehouseFolder, false));

        ApiException error = assertThrows(ApiException.class, () -> resolve(locations, null));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals(AMBIGUOUS_MESSAGE, error.getMessage());
    }
}
