package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.LikePatterns;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.model.FolderAccess;
import id.jakforge.openorchestrator.repository.DashboardRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.PermissionChecker;
import id.jakforge.openorchestrator.security.Permissions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Pencarian menyeluruh di bilah atas. */
@Service
@RequiredArgsConstructor
public class SearchService {

    /**
     * Satu huruf tidak dilayani: hasilnya akan berisi hampir semua yang ada
     * dan tidak menolong siapa pun, sementara biayanya delapan pemindaian tabel.
     */
    static final int MIN_QUERY_LENGTH = 2;
    static final int RESULT_LIMIT = 30;

    private static final String FOLDER_ID_COLUMN = "folderId";
    private static final String PAGE_COLUMN = "page";
    private static final String FOLDERS_PAGE = "folders";

    private final DashboardRepository dashboardRepository;
    private final FolderAccessService folderAccessService;
    private final PermissionChecker permissionChecker;

    /**
     * Hasil yang tidak boleh DILIHAT penanya dibuang, dengan dua aturan:
     *
     * <ul>
     *   <li>Hasil dari folder yang tidak boleh dibuka dibuang: pencarian tidak
     *       boleh menjadi jalan melihat isi folder yang disembunyikan dari
     *       bilah folder.</li>
     *   <li>Hasil yang halamannya tidak boleh DIBACA peran penanya dibuang:
     *       pencarian tidak boleh menjadi jalan memutar untuk melihat nama aset
     *       atau robot yang halamannya sendiri tertutup baginya. Jenis hasil
     *       ("page") sama dengan nama sumber izinnya; folder terlihat bagi
     *       siapa pun yang boleh membukanya.</li>
     * </ul>
     */
    public List<Map<String, Object>> search(OpenOrchestratorPrincipal principal, String query) {
        if (query == null || query.trim().length() < MIN_QUERY_LENGTH) return List.of();

        FolderAccess access = folderAccessService.accessibleFolders(principal);

        return dashboardRepository.search(principal.tenantId(), LikePatterns.containing(query), RESULT_LIMIT)
                .stream()
                .filter(hit -> isInAccessibleFolder(hit, access))
                .filter(hit -> isReadable(hit, principal))
                .toList();
    }

    private static boolean isInAccessibleFolder(Map<String, Object> hit, FolderAccess access) {
        Object folderId = hit.get(FOLDER_ID_COLUMN);
        return folderId == null || access.allows(Uuids.parseOrNull((String) folderId));
    }

    private boolean isReadable(Map<String, Object> hit, OpenOrchestratorPrincipal principal) {
        Object page = hit.get(PAGE_COLUMN);

        return FOLDERS_PAGE.equals(page)
                || permissionChecker.isAllowed(principal, Permissions.of(String.valueOf(page), Permissions.READ));
    }
}
