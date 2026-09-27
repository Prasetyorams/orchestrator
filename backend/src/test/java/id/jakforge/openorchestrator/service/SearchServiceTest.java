package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.repository.DashboardRepository;
import id.jakforge.openorchestrator.repository.FolderRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.PermissionChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pencarian tidak boleh menjadi jalan memutar: yang tidak boleh dibuka lewat
 * bilah folder dan halamannya, juga tidak boleh terlihat di hasil pencarian.
 */
class SearchServiceTest {

    private final UUID assignedFolder = UUID.randomUUID();
    private final UUID otherFolder = UUID.randomUUID();
    private final OpenOrchestratorPrincipal operator =
            new OpenOrchestratorPrincipal(UUID.randomUUID(), UUID.randomUUID(), "budi", "Operator");

    private final FakeDashboardRepository dashboard = new FakeDashboardRepository();

    /** Hanya boleh membaca proses; tidak boleh mengatur folder. */
    private final PermissionChecker processReader = (principal, permission) ->
            Set.of("processes.read").contains(permission);

    private final SearchService searchService = new SearchService(dashboard,
            new FolderAccessService(new AssignedToOneFolder(assignedFolder), processReader), processReader);

    @Test
    @DisplayName("hasil dari folder lain dan dari halaman yang tidak boleh dibaca dibuang")
    void hidesWhatCannotBeOpened() {
        dashboard.hits.add(hit("processes", assignedFolder, "Tagihan"));
        dashboard.hits.add(hit("processes", otherFolder, "Gaji"));
        dashboard.hits.add(hit("robots", null, "PC-Budi"));
        dashboard.hits.add(hit("folders", assignedFolder, "Keuangan"));

        List<Map<String, Object>> results = searchService.search(operator, "ta");

        assertEquals(List.of("Tagihan", "Keuangan"), results.stream().map(result -> result.get("label")).toList());
    }

    @Test
    @DisplayName("kata kunci satu huruf tidak dilayani dan tidak bertanya ke basis data")
    void singleLetterIsIgnored() {
        assertTrue(searchService.search(operator, " a ").isEmpty());
        assertTrue(searchService.search(operator, null).isEmpty());
        assertNull(dashboard.lastPattern);
    }

    @Test
    @DisplayName("% dan _ di kata kunci diloloskan, bukan menjadi pola LIKE")
    void likeWildcardsAreEscaped() {
        searchService.search(operator, " 100%_a ");

        assertEquals("%100\\%\\_a%", dashboard.lastPattern);
    }

    private static Map<String, Object> hit(String page, UUID folderId, String label) {
        Map<String, Object> hit = new HashMap<>();
        hit.put("page", page);
        hit.put("folderId", folderId == null ? null : folderId.toString());
        hit.put("label", label);
        return hit;
    }

    private static final class FakeDashboardRepository extends DashboardRepository {

        final List<Map<String, Object>> hits = new ArrayList<>();
        String lastPattern;

        FakeDashboardRepository() {
            super(null);
        }

        @Override
        public List<Map<String, Object>> search(UUID tenantId, String pattern, int limit) {
            lastPattern = pattern;
            return hits;
        }
    }

    private static final class AssignedToOneFolder extends FolderRepository {

        private final UUID folderId;

        AssignedToOneFolder(UUID folderId) {
            super(null);
            this.folderId = folderId;
        }

        @Override
        public Set<UUID> findAssignedFolderIds(UUID tenantId, UUID userId) {
            return Set.of(folderId);
        }

        @Override
        public Optional<Map<String, Object>> findPersonal(UUID tenantId, UUID userId) {
            return Optional.empty();
        }
    }
}
