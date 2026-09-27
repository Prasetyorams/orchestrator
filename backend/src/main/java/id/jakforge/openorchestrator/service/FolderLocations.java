package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.repository.FolderLocation;

import java.util.List;
import java.util.UUID;

/**
 * Folder mana yang dimaksud oleh permintaan yang menyebut sebuah NAMA.
 *
 * <p>Nama proses dan pemicu unik per folder, bukan per penyewa (V5), jadi
 * satu nama bisa ada di beberapa folder.
 */
public final class FolderLocations {

    private FolderLocations() {
    }

    /**
     * <ul>
     *   <li>Permintaan yang menyebut folder — dasbor selalu menyebutnya —
     *       mendapat yang di folder itu, atau null.</li>
     *   <li>Permintaan tanpa folder — Studio (Start Job) dan pemanggil lama —
     *       mendapat satu-satunya yang bernama itu. Kalau ada beberapa, yang
     *       di folder BAWAAN, karena ke sanalah penerbitan dari Studio
     *       memasangnya. Kalau tidak ada satu pun di folder bawaan, DITOLAK:
     *       menebak berarti menjalankan proses di folder yang tidak dimaksud,
     *       dengan robot folder itu.</li>
     * </ul>
     *
     * @param locations         urutan dari repositori: folder bawaan lebih dulu
     * @param requestedFolderId folder yang disebut permintaan, atau null
     * @param ambiguousMessage  pesan 409 untuk nama yang tidak bisa diputuskan
     * @return folder yang dimaksud, atau null kalau namanya tidak ada (di folder itu)
     */
    public static UUID resolve(List<FolderLocation> locations, UUID requestedFolderId, String ambiguousMessage) {
        if (requestedFolderId != null) {
            for (FolderLocation location : locations) {
                if (requestedFolderId.equals(location.folderId())) return requestedFolderId;
            }

            return null;
        }

        if (locations.isEmpty()) return null;
        if (locations.size() == 1) return locations.getFirst().folderId();

        for (FolderLocation location : locations) {
            if (location.isDefault()) return location.folderId();
        }

        throw ApiException.conflict(ambiguousMessage);
    }
}
