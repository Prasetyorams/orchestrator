package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.common.Uuids;
import id.jakforge.forgehub.dto.request.AssignRobotRequest;
import id.jakforge.forgehub.dto.request.AssignUserRequest;
import id.jakforge.forgehub.dto.request.FolderRequest;
import id.jakforge.forgehub.dto.response.CreatedResponse;
import id.jakforge.forgehub.dto.response.FolderMembersResponse;
import id.jakforge.forgehub.dto.response.FolderTreeResponse;
import id.jakforge.forgehub.model.FolderAccess;
import id.jakforge.forgehub.repository.FolderRepository;
import id.jakforge.forgehub.repository.RobotRepository;
import id.jakforge.forgehub.repository.UserRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.PermissionChecker;
import id.jakforge.forgehub.security.Permissions;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static id.jakforge.forgehub.service.FolderAccessService.FOLDER_NOT_FOUND;
import static id.jakforge.forgehub.service.FolderAccessService.OWNER_ID_COLUMN;

/**
 * Aturan tentang folder: bentuk pohonnya, dan siapa ditugaskan ke mana.
 *
 * <p>Membuat, mengubah, dan menghapus folder masing-masing butuh
 * folders.create, folders.update, dan folders.delete. Siapa boleh MEMBUKA
 * folder mana ada di {@link FolderAccessService}.
 */
@Service
@RequiredArgsConstructor
public class FolderService {

    /** Batas panjang, mengikuti kolomnya di V4. */
    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_DESCRIPTION_LENGTH = 400;

    private static final String ID_COLUMN = "id";
    private static final String PARENT_ID_COLUMN = "parentId";
    private static final String NAME_COLUMN = "name";
    private static final String IS_DEFAULT_COLUMN = "isDefault";
    private static final String ACCESSIBLE_FLAG = "accessible";

    private final FolderRepository folderRepository;
    private final FolderAccessService folderAccessService;
    private final PermissionChecker permissionChecker;
    private final UserRepository userRepository;
    private final RobotRepository robotRepository;

    // -----------------------------------------------------------------
    // Pohon
    // -----------------------------------------------------------------

    /**
     * Isi bilah folder: folder bersama yang boleh dilihat, dan Folder Saya.
     *
     * <p>Folder yang tidak boleh dibuka tetap dikirim kalau ia LELUHUR folder
     * yang boleh dibuka, dengan {@code accessible: false}. Tanpa itu, orang
     * yang hanya ditugaskan ke "Keuangan / Tagihan" melihat "Tagihan" melayang
     * di akar, terlepas dari tempatnya di pohon.
     */
    public FolderTreeResponse getTree(ForgeHubPrincipal principal) {
        List<Map<String, Object>> sharedFolders = folderRepository.findAllShared(principal.tenantId());
        FolderAccess access = folderAccessService.accessibleFolders(principal);

        List<Map<String, Object>> visibleFolders = new ArrayList<>();

        if (access.allFolders()) {
            for (Map<String, Object> folder : sharedFolders) {
                folder.put(ACCESSIBLE_FLAG, true);
                visibleFolders.add(folder);
            }
        } else {
            Map<String, Map<String, Object>> foldersById = new HashMap<>();
            for (Map<String, Object> folder : sharedFolders) foldersById.put(idOf(folder), folder);

            Set<String> shownIds = new HashSet<>();

            for (Map<String, Object> folder : sharedFolders) {
                if (!access.allows(Uuids.parseOrNull(idOf(folder)))) continue;

                // Naik sampai akar, menandai setiap leluhur supaya ikut tampil.
                for (Map<String, Object> current = folder; current != null;
                     current = foldersById.get((String) current.get(PARENT_ID_COLUMN))) {
                    if (!shownIds.add(idOf(current))) break;
                }
            }

            for (Map<String, Object> folder : sharedFolders) {
                if (!shownIds.contains(idOf(folder))) continue;

                folder.put(ACCESSIBLE_FLAG, access.allows(Uuids.parseOrNull(idOf(folder))));
                visibleFolders.add(folder);
            }
        }

        return new FolderTreeResponse(
                visibleFolders,
                folderRepository.findPersonal(principal.tenantId(), principal.userId()).orElse(null),
                permissionChecker.isAllowed(principal, Permissions.FOLDERS_CREATE));
    }

    /** Halaman pengelolaan: semua folder, termasuk folder pribadi setiap orang. */
    public List<Map<String, Object>> findAllForManagement(ForgeHubPrincipal principal) {
        permissionChecker.require(principal, Permissions.FOLDERS_READ);

        return folderRepository.findAllForManagement(principal.tenantId());
    }

    /**
     * Folder Saya milik orang yang meminta, dibuat kalau belum ada.
     *
     * <p>Dibuat saat pertama kali DIBUKA, bukan saat penggunanya dibuat:
     * kebanyakan orang tidak pernah memakainya, dan seratus folder kosong
     * hanya memenuhi halaman pengelolaan.
     */
    @Transactional
    public Map<String, Object> getOrCreatePersonalFolder(ForgeHubPrincipal principal) {
        return folderRepository.findPersonal(principal.tenantId(), principal.userId()).orElseGet(() -> {
            folderRepository.insertPersonal(principal.tenantId(), principal.userId());

            return folderRepository.findPersonal(principal.tenantId(), principal.userId())
                    .orElseThrow(() -> new IllegalStateException("Folder Saya tidak tersimpan."));
        });
    }

    // -----------------------------------------------------------------
    // Membuat, mengubah, menghapus
    // -----------------------------------------------------------------

    @Transactional
    public CreatedResponse create(ForgeHubPrincipal principal, FolderRequest request) {
        permissionChecker.require(principal, Permissions.FOLDERS_CREATE);

        String name = validateName(request.name());
        UUID parentId = resolveParent(principal.tenantId(), request.parentId());

        if (folderRepository.isNameTaken(principal.tenantId(), parentId, name, null)) {
            throw nameTaken(name);
        }

        UUID folderId = UUID.randomUUID();

        try {
            folderRepository.insert(folderId, principal.tenantId(), parentId, name,
                    normalizeDescription(request.description()));
        } catch (DuplicateKeyException e) {
            // Orang lain membuat nama yang sama di antara pemeriksaan dan
            // penyimpanan. Jawabannya sama dengan pemeriksaan di atas.
            throw nameTaken(name);
        }

        if (parentId != null) folderRepository.copyAssignments(principal.tenantId(), parentId, folderId);

        return CreatedResponse.of(folderId);
    }

    /**
     * Ganti nama, keterangan, atau induk.
     *
     * <p>Folder bawaan boleh berganti nama tapi tetap di akar: folder itu
     * tempat jatuhnya segala yang datang tanpa folder, dan tempat seperti itu
     * tidak boleh tersembunyi di dalam cabang lain.
     */
    @Transactional
    public void update(ForgeHubPrincipal principal, String folderIdText, FolderRequest request) {
        permissionChecker.require(principal, Permissions.FOLDERS_UPDATE);

        UUID folderId = parseFolderId(folderIdText);
        Map<String, Object> folder = findFolder(principal.tenantId(), folderId);

        if (folder.get(OWNER_ID_COLUMN) != null) {
            throw ApiException.badRequest("Folder Saya tidak bisa diganti nama atau dipindah.");
        }

        // Keterangan kosong berarti dihapus: dialog sunting selalu mengirimnya,
        // dan mengosongkan isiannya memang dimaksudkan untuk membuangnya.
        String name = request.name() == null ? (String) folder.get(NAME_COLUMN) : validateName(request.name());
        String description = normalizeDescription(request.description());

        UUID parentId = Uuids.parseOrNull((String) folder.get(PARENT_ID_COLUMN));

        if (request.parentIdProvided()) {
            UUID newParentId = resolveParent(principal.tenantId(), request.parentId());

            if (Boolean.TRUE.equals(folder.get(IS_DEFAULT_COLUMN)) && newParentId != null) {
                throw ApiException.badRequest("Folder bawaan harus tetap di akar.");
            }

            if (newParentId != null && (newParentId.equals(folderId)
                    || folderRepository.findDescendantIds(principal.tenantId(), folderId).contains(newParentId))) {
                throw ApiException.badRequest("Folder tidak bisa dipindah ke dalam dirinya sendiri.");
            }

            parentId = newParentId;
        }

        if (folderRepository.isNameTaken(principal.tenantId(), parentId, name, folderId)) {
            throw nameTaken(name);
        }

        try {
            folderRepository.update(principal.tenantId(), folderId, name, description, parentId);
        } catch (DuplicateKeyException e) {
            throw nameTaken(name);
        }
    }

    /**
     * Hapus folder yang sudah KOSONG.
     *
     * <p>Folder yang masih berisi ditolak, bukan dikosongkan diam-diam:
     * menghapus satu folder tidak boleh sekaligus menghapus proses, aset, dan
     * antrean yang mungkin masih dipakai robot di tempat lain.
     *
     * <p>Riwayat pekerjaannya tidak menghalangi. Riwayat itu dipindah ke
     * induknya — atau ke folder bawaan untuk folder di akar — supaya tetap
     * bisa dibaca.
     */
    @Transactional
    public void delete(ForgeHubPrincipal principal, String folderIdText) {
        permissionChecker.require(principal, Permissions.FOLDERS_DELETE);

        UUID folderId = parseFolderId(folderIdText);
        Map<String, Object> folder = findFolder(principal.tenantId(), folderId);

        if (Boolean.TRUE.equals(folder.get(IS_DEFAULT_COLUMN))) {
            throw ApiException.badRequest("Folder bawaan tidak bisa dihapus.");
        }

        if (folderRepository.hasChildren(principal.tenantId(), folderId)) {
            throw ApiException.badRequest("Folder '" + folder.get(NAME_COLUMN)
                    + "' masih punya subfolder. Pindahkan atau hapus subfoldernya dulu.");
        }

        if (folderRepository.countContents(principal.tenantId(), folderId) > 0) {
            throw ApiException.badRequest("Folder '" + folder.get(NAME_COLUMN)
                    + "' masih berisi proses, pemicu, antrean, aset, atau ember penyimpanan. Pindahkan atau hapus isinya dulu.");
        }

        UUID parentId = Uuids.parseOrNull((String) folder.get(PARENT_ID_COLUMN));
        folderRepository.moveJobs(principal.tenantId(), folderId,
                parentId != null ? parentId : folderRepository.findDefaultFolderId(principal.tenantId()));

        folderRepository.delete(principal.tenantId(), folderId);
    }

    // -----------------------------------------------------------------
    // Penugasan
    // -----------------------------------------------------------------

    /** Pengguna dan robot yang ditugaskan; boleh dilihat siapa pun yang boleh membuka foldernya. */
    public FolderMembersResponse getMembers(ForgeHubPrincipal principal, String folderIdText) {
        UUID folderId = parseFolderId(folderIdText);
        Map<String, Object> folder = folderAccessService.requireAccessibleFolder(principal, folderId);

        boolean canManageUsers = folderAccessService.canManageFolders(principal) && folder.get(OWNER_ID_COLUMN) == null;
        boolean canManageRobots = canManageRobots(principal, folder);

        // Id pemilik tidak ikut keluar; yang perlu diketahui layar sudah ada di "personal".
        folder.remove(OWNER_ID_COLUMN);

        return new FolderMembersResponse(
                folder,
                folderRepository.findAssignedUsers(principal.tenantId(), folderId),
                folderRepository.findAssignedRobots(principal.tenantId(), folderId),
                canManageUsers,
                canManageRobots);
    }

    @Transactional
    public void assignUser(ForgeHubPrincipal principal, String folderIdText, AssignUserRequest request) {
        permissionChecker.require(principal, Permissions.FOLDERS_UPDATE);

        UUID folderId = requireSharedFolder(principal, folderIdText);
        UUID userId = userRepository.findIdByUsername(principal.tenantId(), request.username())
                .orElseThrow(() -> ApiException.notFound("Pengguna tidak ada."));

        folderRepository.assignUser(principal.tenantId(), folderId, userId);
    }

    @Transactional
    public void unassignUser(ForgeHubPrincipal principal, String folderIdText, String username) {
        permissionChecker.require(principal, Permissions.FOLDERS_UPDATE);

        UUID folderId = requireSharedFolder(principal, folderIdText);
        UUID userId = userRepository.findIdByUsername(principal.tenantId(), username).orElse(null);

        if (userId == null || folderRepository.unassignUser(principal.tenantId(), folderId, userId) == 0) {
            throw ApiException.notFound("Pengguna itu tidak ditugaskan ke folder ini.");
        }
    }

    @Transactional
    public void assignRobot(ForgeHubPrincipal principal, String folderIdText, AssignRobotRequest request) {
        UUID folderId = requireRobotManagement(principal, folderIdText);
        UUID robotId = robotRepository.findIdByName(principal.tenantId(), request.robotName())
                .orElseThrow(() -> ApiException.notFound("Robot '" + request.robotName() + "' tidak ada."));

        folderRepository.assignRobot(principal.tenantId(), folderId, robotId);
    }

    @Transactional
    public void unassignRobot(ForgeHubPrincipal principal, String folderIdText, String robotName) {
        UUID folderId = requireRobotManagement(principal, folderIdText);
        UUID robotId = robotRepository.findIdByName(principal.tenantId(), robotName).orElse(null);

        if (robotId == null || folderRepository.unassignRobot(principal.tenantId(), folderId, robotId) == 0) {
            throw ApiException.notFound("Robot itu tidak ditugaskan ke folder ini.");
        }
    }

    /**
     * Robot folder bersama diatur pengelola folder. Robot Folder Saya diatur
     * pemiliknya: itu tempat kerja pribadinya, dan menunggu Administrator
     * hanya untuk menjalankan prosesnya sendiri tidak masuk akal.
     */
    private boolean canManageRobots(ForgeHubPrincipal principal, Map<String, Object> folder) {
        Object ownerId = folder.get(OWNER_ID_COLUMN);

        if (ownerId != null) {
            return principal.userId().toString().equals(ownerId) || folderAccessService.canManageFolders(principal);
        }

        return folderAccessService.canManageFolders(principal);
    }

    private UUID requireRobotManagement(ForgeHubPrincipal principal, String folderIdText) {
        UUID folderId = parseFolderId(folderIdText);
        Map<String, Object> folder = folderAccessService.requireAccessibleFolder(principal, folderId);

        if (!canManageRobots(principal, folder)) throw PermissionChecker.denied(Permissions.FOLDERS_UPDATE);

        return folderId;
    }

    // -----------------------------------------------------------------
    // Alat
    // -----------------------------------------------------------------

    private Map<String, Object> findFolder(UUID tenantId, UUID folderId) {
        return folderRepository.findById(tenantId, folderId).orElseThrow(() -> ApiException.notFound(FOLDER_NOT_FOUND));
    }

    private UUID requireSharedFolder(ForgeHubPrincipal principal, String folderIdText) {
        UUID folderId = parseFolderId(folderIdText);
        Map<String, Object> folder = findFolder(principal.tenantId(), folderId);

        if (folder.get(OWNER_ID_COLUMN) != null) {
            throw ApiException.badRequest(
                    "Folder Saya hanya milik pemiliknya; pengguna lain tidak bisa ditugaskan ke sana.");
        }

        return folderId;
    }

    /** Induk yang diminta: harus ada, dan bukan folder pribadi. */
    private UUID resolveParent(UUID tenantId, String parentIdText) {
        if (parentIdText == null) return null;

        UUID parentId = Uuids.parseOrNull(parentIdText);
        Map<String, Object> parent = parentId == null
                ? null
                : folderRepository.findById(tenantId, parentId).orElse(null);

        if (parent == null) throw ApiException.notFound("Folder induk tidak ada.");

        if (parent.get(OWNER_ID_COLUMN) != null) {
            throw ApiException.badRequest("Folder Saya tidak bisa punya subfolder.");
        }

        return parentId;
    }

    /**
     * Nama folder: wajib, tidak terlalu panjang, dan tanpa garis miring.
     *
     * <p>Garis miring dipakai untuk menuliskan jalurnya — "Keuangan / Tagihan"
     * — dan nama yang memuatnya membuat jalur itu terbaca sebagai folder lain.
     */
    private static String validateName(String name) {
        if (name == null || name.isBlank()) throw ApiException.badRequest("Nama folder wajib diisi.");

        String trimmed = name.trim();

        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw ApiException.badRequest("Nama folder paling panjang " + MAX_NAME_LENGTH + " karakter.");
        }

        if (trimmed.contains("/") || trimmed.contains("\\")) {
            throw ApiException.badRequest("Nama folder tidak boleh memuat garis miring.");
        }

        return trimmed;
    }

    private static String normalizeDescription(String description) {
        if (description == null) return null;

        String trimmed = description.trim();
        if (trimmed.isEmpty()) return null;

        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw ApiException.badRequest("Keterangan paling panjang " + MAX_DESCRIPTION_LENGTH + " karakter.");
        }

        return trimmed;
    }

    private static ApiException nameTaken(String name) {
        return ApiException.conflict("Folder '" + name + "' sudah ada di tempat itu.");
    }

    private static UUID parseFolderId(String folderIdText) {
        UUID folderId = Uuids.parseOrNull(folderIdText);
        if (folderId == null) throw ApiException.notFound(FOLDER_NOT_FOUND);
        return folderId;
    }

    private static String idOf(Map<String, Object> folder) {
        return (String) folder.get(ID_COLUMN);
    }
}
