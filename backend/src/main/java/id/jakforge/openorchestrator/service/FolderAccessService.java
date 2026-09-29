package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.model.FolderAccess;
import id.jakforge.openorchestrator.repository.FolderRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.PermissionChecker;
import id.jakforge.openorchestrator.security.Permissions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Siapa boleh membuka folder mana.
 *
 * <p>HAK di sini sederhana, dan sengaja begitu:
 *
 * <ul>
 *   <li>Peran yang boleh MENUGASKAN orang ke folder (folders.update) melihat
 *       semua folder bersama: ia toh bisa menugaskan dirinya sendiri ke mana
 *       pun, jadi menyembunyikan folder darinya tidak melindungi apa-apa.
 *       Administrator termasuk di sini.</li>
 *   <li>Pengguna lain hanya melihat folder tempat ia ditugaskan, ditambah
 *       Folder Saya miliknya sendiri.</li>
 *   <li>Folder Saya hanya terlihat oleh pemiliknya di bilah folder.
 *       Pengelola folder tetap bisa membukanya dari halaman pengelolaan,
 *       karena harus ada yang bisa membereskannya kalau pemiliknya sudah
 *       pergi.</li>
 * </ul>
 *
 * <p>Pembatasan itu berlaku untuk permintaan yang MENYEBUT folder — dasbor
 * selalu menyebutnya. Permintaan tanpa folder, yang dikirim Studio dan
 * JakRunner, tetap menjangkau seluruh penyewa seperti sebelum folder ada:
 * robot mencari aset dan antrean lewat nama, tanpa tahu foldernya.
 *
 * <p>Dipisah dari {@link FolderService}: hampir setiap layanan memerlukan
 * aturan ini, sedangkan pengelolaan pohon folder hanya diperlukan satu.
 */
@Service
@RequiredArgsConstructor
public class FolderAccessService {

    static final String FOLDER_NOT_FOUND = "Folder tidak ada.";
    static final String OWNER_ID_COLUMN = "ownerId";

    private final FolderRepository folderRepository;
    private final PermissionChecker permissionChecker;

    /** Melihat dan mengatur semua folder bersama — lihat keterangan kelas. */
    public boolean canManageFolders(OpenOrchestratorPrincipal principal) {
        return permissionChecker.isAllowed(principal, Permissions.FOLDERS_UPDATE);
    }

    /** Folder yang boleh dibuka seseorang. */
    public FolderAccess accessibleFolders(OpenOrchestratorPrincipal principal) {
        if (principal.isExecutor()) return FolderAccess.only(executorFolders(principal));
        if (canManageFolders(principal)) return FolderAccess.all();

        Set<UUID> folderIds = new HashSet<>(
                folderRepository.findAssignedFolderIds(principal.tenantId(), principal.userId()));

        folderRepository.findPersonal(principal.tenantId(), principal.userId())
                .ifPresent(personal -> folderIds.add(Uuids.parseOrNull((String) personal.get("id"))));

        return FolderAccess.only(folderIds);
    }

    /**
     * Folder dari parameter atau badan permintaan, sesudah diperiksa haknya.
     *
     * @return null kalau permintaannya tidak menyebut folder — artinya seluruh
     *         penyewa, seperti sebelum folder ada
     */
    public UUID resolveFolderFilter(OpenOrchestratorPrincipal principal, String folderId) {
        if (folderId == null || folderId.isBlank()) return null;

        UUID id = Uuids.parseOrNull(folderId);
        if (id == null) throw ApiException.notFound(FOLDER_NOT_FOUND);

        requireAccessibleFolder(principal, id);

        return id;
    }

    /**
     * Folder itu, kalau ada dan boleh dibuka — termasuk {@code ownerId}-nya.
     *
     * <p>Folder pribadi orang lain dijawab "tidak ada", bukan "tidak berhak":
     * jawaban kedua memberi tahu bahwa folder itu ada.
     */
    public Map<String, Object> requireAccessibleFolder(OpenOrchestratorPrincipal principal, UUID folderId) {
        Map<String, Object> folder = folderRepository.findById(principal.tenantId(), folderId)
                .orElseThrow(() -> ApiException.notFound(FOLDER_NOT_FOUND));

        if (principal.isExecutor()) {
            if (!executorFolders(principal).contains(folderId)) throw ApiException.notFound(FOLDER_NOT_FOUND);
            return folder;
        }

        if (canManageFolders(principal)) return folder;

        Object ownerId = folder.get(OWNER_ID_COLUMN);

        if (ownerId != null) {
            if (!principal.userId().toString().equals(ownerId)) throw ApiException.notFound(FOLDER_NOT_FOUND);
            return folder;
        }

        if (!folderRepository.findAssignedFolderIds(principal.tenantId(), principal.userId()).contains(folderId)) {
            throw ApiException.forbidden("Anda tidak ditugaskan ke folder ini.");
        }

        return folder;
    }

    /**
     * Executor sebuah job melihat folder tempat robotnya ditugaskan, ditambah
     * folder job-nya sendiri — seperti robot UiPath di foldernya.
     */
    private Set<UUID> executorFolders(OpenOrchestratorPrincipal executor) {
        Set<UUID> folderIds = new HashSet<>(folderRepository.findRobotFolderIds(executor.tenantId(), executor.robotId()));
        if (executor.folderId() != null) folderIds.add(executor.folderId());
        return folderIds;
    }
}
