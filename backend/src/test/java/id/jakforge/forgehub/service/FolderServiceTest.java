package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.request.AssignRobotRequest;
import id.jakforge.forgehub.dto.request.AssignUserRequest;
import id.jakforge.forgehub.dto.request.FolderRequest;
import id.jakforge.forgehub.dto.response.CreatedResponse;
import id.jakforge.forgehub.repository.FolderRepository;
import id.jakforge.forgehub.repository.RobotRepository;
import id.jakforge.forgehub.repository.UserRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.PermissionChecker;
import id.jakforge.forgehub.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hak dan bentuk pohon folder.
 *
 * <p>Pohonnya:
 *
 * <pre>
 *   Shared (bawaan)
 *   Keuangan
 *   └── Tagihan
 *       └── Arsip
 *   Gudang
 * </pre>
 *
 * dan seorang Automation User yang hanya ditugaskan ke Tagihan.
 */
class FolderServiceTest {

    private final UUID tenantId = UUID.randomUUID();

    private final ForgeHubPrincipal administrator =
            new ForgeHubPrincipal(UUID.randomUUID(), tenantId, "FH_Admin", "Administrator");
    private final ForgeHubPrincipal automationUser =
            new ForgeHubPrincipal(UUID.randomUUID(), tenantId, "budi", "Automation User");

    // Administrator boleh segalanya; Automation User tidak punya izin folder apa pun.
    private final PermissionChecker administratorOnly = (principal, permission) ->
            "Administrator".equals(principal.role());

    private final FakeFolderRepository folders = new FakeFolderRepository();
    private final FakeUserRepository users = new FakeUserRepository();
    private final FakeRobotRepository robots = new FakeRobotRepository();

    private final FolderAccessService folderAccess = new FolderAccessService(folders, administratorOnly);
    private final FolderService folderService =
            new FolderService(folders, folderAccess, administratorOnly, users, robots);

    private final UUID sharedFolder = folders.add(null, "Shared", true, null);
    private final UUID financeFolder = folders.add(null, "Keuangan", false, null);
    private final UUID invoicesFolder = folders.add(financeFolder, "Tagihan", false, null);
    private final UUID archiveFolder = folders.add(invoicesFolder, "Arsip", false, null);
    private final UUID warehouseFolder = folders.add(null, "Gudang", false, null);

    {
        folders.userAssignments.add(invoicesFolder + "/" + automationUser.userId());
    }

    // ---------- pohon ----------

    @Test
    @DisplayName("Administrator melihat semua folder bersama, semuanya bisa dibuka")
    void administratorSeesAllFolders() {
        List<Map<String, Object>> tree = treeOf(administrator);

        assertEquals(5, tree.size());
        assertTrue(tree.stream().allMatch(folder -> Boolean.TRUE.equals(folder.get("accessible"))));
    }

    @Test
    @DisplayName("pengguna lain melihat foldernya beserta leluhurnya, leluhur itu tidak bisa dibuka")
    void userSeesBranchWithAncestors() {
        Map<String, Boolean> visible = new HashMap<>();
        for (Map<String, Object> folder : treeOf(automationUser)) {
            visible.put((String) folder.get("name"), (Boolean) folder.get("accessible"));
        }

        assertEquals(Map.of("Keuangan", false, "Tagihan", true), visible);
    }

    @Test
    @DisplayName("Folder Saya milik sendiri ikut; milik orang lain tidak pernah muncul di pohon")
    void personalFolderOnlyForOwner() {
        folderService.getOrCreatePersonalFolder(automationUser);

        assertEquals("Folder Saya", folderService.getTree(automationUser).personal().get("name"));
        assertNull(folderService.getTree(administrator).personal());
        assertTrue(treeOf(administrator).stream().noneMatch(folder -> "Folder Saya".equals(folder.get("name"))));
    }

    // ---------- hak ----------

    @Test
    @DisplayName("folder yang tidak ditugaskan ditolak 403; Folder Saya orang lain dijawab 404")
    void accessRules() {
        assertStatus(HttpStatus.FORBIDDEN, () -> folderAccess.resolveFolderFilter(automationUser, warehouseFolder.toString()));
        assertStatus(HttpStatus.FORBIDDEN, () -> folderAccess.resolveFolderFilter(automationUser, financeFolder.toString()));
        assertEquals(invoicesFolder, folderAccess.resolveFolderFilter(automationUser, invoicesFolder.toString()));

        UUID administratorsFolder = UUID.fromString(
                (String) folderService.getOrCreatePersonalFolder(administrator).get("id"));
        assertStatus(HttpStatus.NOT_FOUND,
                () -> folderAccess.resolveFolderFilter(automationUser, administratorsFolder.toString()));

        UUID usersOwnFolder = UUID.fromString((String) folderService.getOrCreatePersonalFolder(automationUser).get("id"));
        assertEquals(usersOwnFolder, folderAccess.resolveFolderFilter(automationUser, usersOwnFolder.toString()));
    }

    @Test
    @DisplayName("tanpa folder berarti seluruh penyewa; id yang rusak dijawab 404")
    void noFolderMeansWholeTenant() {
        assertNull(folderAccess.resolveFolderFilter(automationUser, null));
        assertNull(folderAccess.resolveFolderFilter(automationUser, " "));
        assertStatus(HttpStatus.NOT_FOUND, () -> folderAccess.resolveFolderFilter(automationUser, "bukan-uuid"));
    }

    // ---------- membuat dan mengubah ----------

    @Test
    @DisplayName("hanya peran dengan folders.create yang membuat folder")
    void onlyPermittedRoleCreatesFolders() {
        assertStatus(HttpStatus.FORBIDDEN, () -> folderService.create(automationUser, folderRequest("Baru", null)));
    }

    @Test
    @DisplayName("subfolder baru mewarisi pengguna dan robot induknya")
    void subfolderInheritsAssignments() {
        folders.robotAssignments.add(invoicesFolder + "/" + UUID.randomUUID());

        CreatedResponse created = folderService.create(administrator, folderRequest("Bulanan", invoicesFolder.toString()));
        UUID newFolder = UUID.fromString(created.id());

        assertTrue(folders.userAssignments.contains(newFolder + "/" + automationUser.userId()));
        assertEquals(1, folders.robotAssignments.stream().filter(robot -> robot.startsWith(newFolder + "/")).count());
    }

    @Test
    @DisplayName("nama kembar di tempat yang sama ditolak 409, tanpa membedakan huruf besar")
    void duplicateNameIsConflict() {
        assertStatus(HttpStatus.CONFLICT, () -> folderService.create(administrator, folderRequest("keuangan", null)));

        // Di induk lain, nama yang sama boleh.
        folderService.create(administrator, folderRequest("Keuangan", warehouseFolder.toString()));
    }

    @Test
    @DisplayName("nama dengan garis miring ditolak: garis miring dipakai untuk menuliskan jalur")
    void slashInNameIsRejected() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> folderService.create(administrator, folderRequest("A/B", null)));
    }

    @Test
    @DisplayName("folder tidak bisa dipindah ke dalam cabangnya sendiri")
    void cannotMoveIntoOwnBranch() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> folderService.update(administrator, financeFolder.toString(),
                folderRequest("Keuangan", archiveFolder.toString())));
        assertStatus(HttpStatus.BAD_REQUEST, () -> folderService.update(administrator, financeFolder.toString(),
                folderRequest("Keuangan", financeFolder.toString())));

        folderService.update(administrator, archiveFolder.toString(), folderRequest("Arsip", warehouseFolder.toString()));
        assertEquals(warehouseFolder.toString(), folders.rows.get(archiveFolder).get("parentId"));
    }

    @Test
    @DisplayName("folder bawaan boleh berganti nama tapi tetap di akar")
    void defaultFolderStaysAtRoot() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> folderService.update(administrator, sharedFolder.toString(),
                folderRequest("Shared", warehouseFolder.toString())));

        folderService.update(administrator, sharedFolder.toString(), folderRequest("Bersama", null));
        assertEquals("Bersama", folders.rows.get(sharedFolder).get("name"));
    }

    @Test
    @DisplayName("parentId yang tidak disebut membiarkan foldernya di tempatnya")
    void missingParentIdKeepsParent() {
        folderService.update(administrator, archiveFolder.toString(),
                FolderRequest.fromBody(Map.<String, Object>of("name", "Arsip Lama")));

        assertEquals("Arsip Lama", folders.rows.get(archiveFolder).get("name"));
        assertEquals(invoicesFolder.toString(), folders.rows.get(archiveFolder).get("parentId"));
    }

    // ---------- menghapus ----------

    @Test
    @DisplayName("folder bawaan, folder bersubfolder, dan folder berisi tidak bisa dihapus")
    void deleteIsRejected() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> folderService.delete(administrator, sharedFolder.toString()));
        assertStatus(HttpStatus.BAD_REQUEST, () -> folderService.delete(administrator, invoicesFolder.toString()));

        folders.contentCounts.put(warehouseFolder, 2L);
        assertStatus(HttpStatus.BAD_REQUEST, () -> folderService.delete(administrator, warehouseFolder.toString()));
    }

    @Test
    @DisplayName("folder kosong terhapus, dan riwayat pekerjaannya pindah ke induknya")
    void emptyFolderIsDeletedAndJobsMoveToParent() {
        folderService.delete(administrator, archiveFolder.toString());

        assertFalse(folders.rows.containsKey(archiveFolder));
        assertEquals(List.of(archiveFolder + "->" + invoicesFolder), folders.movedJobs);
    }

    @Test
    @DisplayName("riwayat folder akar pindah ke folder bawaan")
    void rootFolderJobsMoveToDefaultFolder() {
        folderService.delete(administrator, warehouseFolder.toString());

        assertEquals(List.of(warehouseFolder + "->" + sharedFolder), folders.movedJobs);
    }

    // ---------- penugasan ----------

    @Test
    @DisplayName("robot Folder Saya diatur pemiliknya sendiri, robot folder bersama hanya oleh pengelola folder")
    void personalFolderRobotsManagedByOwner() {
        robots.idsByName.put("PC-Budi", UUID.randomUUID());
        String personalFolder = (String) folderService.getOrCreatePersonalFolder(automationUser).get("id");

        folderService.assignRobot(automationUser, personalFolder, new AssignRobotRequest("PC-Budi"));
        assertTrue(folders.robotAssignments.contains(personalFolder + "/" + robots.idsByName.get("PC-Budi")));

        assertStatus(HttpStatus.FORBIDDEN, () ->
                folderService.assignRobot(automationUser, invoicesFolder.toString(), new AssignRobotRequest("PC-Budi")));
    }

    @Test
    @DisplayName("pengguna tidak bisa ditugaskan ke Folder Saya orang lain")
    void cannotAssignUserToPersonalFolder() {
        users.idsByUsername.put("budi", automationUser.userId());
        String personalFolder = (String) folderService.getOrCreatePersonalFolder(administrator).get("id");

        assertStatus(HttpStatus.BAD_REQUEST, () ->
                folderService.assignUser(administrator, personalFolder, new AssignUserRequest("budi")));
    }

    @Test
    @DisplayName("menugaskan pengguna yang tidak ada dijawab 404")
    void assigningUnknownUserIsNotFound() {
        assertStatus(HttpStatus.NOT_FOUND, () ->
                folderService.assignUser(administrator, invoicesFolder.toString(), new AssignUserRequest("siapa")));
    }

    // ---------- alat ----------

    private List<Map<String, Object>> treeOf(ForgeHubPrincipal principal) {
        return folderService.getTree(principal).folders();
    }

    private static FolderRequest folderRequest(String name, String parentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("parentId", parentId);
        return FolderRequest.fromBody(body);
    }

    private static void assertStatus(HttpStatus status, Executable action) {
        assertEquals(status, assertThrows(ApiException.class, action).status());
    }

    /**
     * Tabel folder di memori, meniru kueri FolderRepository yang dipakai
     * layanannya. Baris berbentuk seperti keluaran Database: id sebagai teks.
     */
    private static final class FakeFolderRepository extends FolderRepository {

        final Map<UUID, Map<String, Object>> rows = new LinkedHashMap<>();
        final Set<String> userAssignments = new HashSet<>();
        final Set<String> robotAssignments = new HashSet<>();
        final Map<UUID, Long> contentCounts = new HashMap<>();
        final List<String> movedJobs = new ArrayList<>();

        FakeFolderRepository() {
            super(null);
        }

        UUID add(UUID parentId, String name, boolean isDefault, UUID ownerId) {
            UUID folderId = UUID.randomUUID();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", folderId.toString());
            row.put("parentId", parentId == null ? null : parentId.toString());
            row.put("name", name);
            row.put("description", null);
            row.put("isDefault", isDefault);
            row.put("personal", ownerId != null);
            row.put("ownerId", ownerId == null ? null : ownerId.toString());
            rows.put(folderId, row);

            return folderId;
        }

        private static Map<String, Object> withoutOwner(Map<String, Object> row) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.remove("ownerId");
            return copy;
        }

        @Override
        public List<Map<String, Object>> findAllShared(UUID tenantId) {
            return rows.values().stream().filter(row -> row.get("ownerId") == null)
                    .map(FakeFolderRepository::withoutOwner).toList();
        }

        @Override
        public Optional<Map<String, Object>> findPersonal(UUID tenantId, UUID userId) {
            return rows.values().stream().filter(row -> userId.toString().equals(row.get("ownerId")))
                    .findFirst().map(FakeFolderRepository::withoutOwner);
        }

        @Override
        public Optional<Map<String, Object>> findById(UUID tenantId, UUID folderId) {
            Map<String, Object> row = rows.get(folderId);
            return row == null ? Optional.empty() : Optional.of(new LinkedHashMap<>(row));
        }

        @Override
        public Set<UUID> findAssignedFolderIds(UUID tenantId, UUID userId) {
            Set<UUID> folderIds = new HashSet<>();
            for (String assignment : userAssignments) {
                if (assignment.endsWith("/" + userId)) folderIds.add(UUID.fromString(assignment.substring(0, 36)));
            }
            return folderIds;
        }

        @Override
        public UUID findDefaultFolderId(UUID tenantId) {
            return rows.entrySet().stream().filter(entry -> Boolean.TRUE.equals(entry.getValue().get("isDefault")))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
        }

        @Override
        public Set<UUID> findDescendantIds(UUID tenantId, UUID folderId) {
            Set<UUID> descendants = new HashSet<>();
            for (Map.Entry<UUID, Map<String, Object>> entry : rows.entrySet()) {
                if (folderId.toString().equals(entry.getValue().get("parentId"))) {
                    descendants.add(entry.getKey());
                    descendants.addAll(findDescendantIds(tenantId, entry.getKey()));
                }
            }
            return descendants;
        }

        @Override
        public boolean isNameTaken(UUID tenantId, UUID parentId, String name, UUID excludedFolderId) {
            return rows.entrySet().stream().anyMatch(entry -> entry.getValue().get("ownerId") == null
                    && Objects.equals(entry.getValue().get("parentId"), parentId == null ? null : parentId.toString())
                    && ((String) entry.getValue().get("name")).equalsIgnoreCase(name)
                    && !entry.getKey().equals(excludedFolderId));
        }

        @Override
        public boolean hasChildren(UUID tenantId, UUID folderId) {
            return rows.values().stream().anyMatch(row -> folderId.toString().equals(row.get("parentId")));
        }

        @Override
        public long countContents(UUID tenantId, UUID folderId) {
            return contentCounts.getOrDefault(folderId, 0L);
        }

        @Override
        public void insert(UUID folderId, UUID tenantId, UUID parentId, String name, String description) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", folderId.toString());
            row.put("parentId", parentId == null ? null : parentId.toString());
            row.put("name", name);
            row.put("description", description);
            row.put("isDefault", false);
            row.put("personal", false);
            row.put("ownerId", null);
            rows.put(folderId, row);
        }

        @Override
        public void insertPersonal(UUID tenantId, UUID userId) {
            add(null, PERSONAL_FOLDER_NAME, false, userId);
        }

        @Override
        public void update(UUID tenantId, UUID folderId, String name, String description, UUID parentId) {
            Map<String, Object> row = rows.get(folderId);
            row.put("name", name);
            row.put("description", description);
            row.put("parentId", parentId == null ? null : parentId.toString());
        }

        @Override
        public void copyAssignments(UUID tenantId, UUID sourceFolderId, UUID targetFolderId) {
            for (String user : new ArrayList<>(userAssignments)) {
                if (user.startsWith(sourceFolderId + "/")) userAssignments.add(targetFolderId + user.substring(36));
            }
            for (String robot : new ArrayList<>(robotAssignments)) {
                if (robot.startsWith(sourceFolderId + "/")) robotAssignments.add(targetFolderId + robot.substring(36));
            }
        }

        @Override
        public void moveJobs(UUID tenantId, UUID sourceFolderId, UUID targetFolderId) {
            movedJobs.add(sourceFolderId + "->" + targetFolderId);
        }

        @Override
        public int delete(UUID tenantId, UUID folderId) {
            return rows.remove(folderId) == null ? 0 : 1;
        }

        @Override
        public int assignUser(UUID tenantId, UUID folderId, UUID userId) {
            return userAssignments.add(folderId + "/" + userId) ? 1 : 0;
        }

        @Override
        public int assignRobot(UUID tenantId, UUID folderId, UUID robotId) {
            return robotAssignments.add(folderId + "/" + robotId) ? 1 : 0;
        }
    }

    private static final class FakeUserRepository extends UserRepository {

        final Map<String, UUID> idsByUsername = new HashMap<>();

        FakeUserRepository() {
            super(null);
        }

        @Override
        public Optional<UUID> findIdByUsername(UUID tenantId, String username) {
            return Optional.ofNullable(idsByUsername.get(username));
        }
    }

    private static final class FakeRobotRepository extends RobotRepository {

        final Map<String, UUID> idsByName = new HashMap<>();

        FakeRobotRepository() {
            super(null, TestProperties.defaults());
        }

        @Override
        public Optional<UUID> findIdByName(UUID tenantId, String name) {
            return Optional.ofNullable(idsByName.get(name));
        }
    }
}
