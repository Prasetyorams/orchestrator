package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.dto.request.MoveToFolderRequest;
import id.jakforge.openorchestrator.dto.request.SaveProcessRequest;
import id.jakforge.openorchestrator.repository.PackageRepository;
import id.jakforge.openorchestrator.repository.ProcessRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.PermissionChecker;
import id.jakforge.openorchestrator.security.Permissions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Aturan tentang proses: paket mana yang dijalankan, di folder mana. */
@Service
@RequiredArgsConstructor
public class ProcessService {

    static final String DEFAULT_ENVIRONMENT = "Production";

    private final ProcessRepository processRepository;
    private final PackageRepository packageRepository;
    private final FolderAccessService folderAccessService;
    private final PermissionChecker permissionChecker;

    /** Tanpa {@code folderId}: seluruh penyewa, bentuk yang dibaca Studio dan JakRunner. */
    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal, String folderId) {
        return processRepository.findAll(principal.tenantId(),
                folderAccessService.resolveFolderFilter(principal, folderId));
    }

    /**
     * Folder proses yang dimaksud sebuah permintaan, atau null kalau proses
     * itu tidak ada (di folder yang disebut). Aturannya di {@link FolderLocations#resolve}.
     */
    public UUID resolveProcessFolder(UUID tenantId, String processName, UUID folderId) {
        return FolderLocations.resolve(processRepository.findLocations(tenantId, processName), folderId,
                "Proses '" + processName + "' ada di beberapa folder. Sebutkan foldernya.");
    }

    /**
     * Nama yang sudah ada DI FOLDER ITU diperbarui, bukan ditolak.
     *
     * <p>Menerbitkan ulang versi yang lebih baru adalah hal yang paling sering
     * dilakukan, dan menolaknya sebagai "sudah ada" memaksa orang menghapus
     * dulu — yang berarti sesaat prosesnya tidak ada sama sekali.
     *
     * <p>Nama yang sama di folder LAIN tidak disentuh dan tidak menghalangi:
     * itu proses lain, dengan versi, pemicu, dan riwayatnya sendiri — seperti
     * paket yang sama dipasang di dua folder Orchestrator.
     *
     * <p>processes.create untuk yang baru, processes.update untuk yang sudah
     * ada — baru ketahuan di sini, sesudah tahu apakah namanya sudah ada.
     */
    @Transactional
    public void save(OpenOrchestratorPrincipal principal, SaveProcessRequest request) {
        UUID tenantId = principal.tenantId();
        UUID folderId = folderAccessService.resolveFolderFilter(principal, request.folderId());

        // Dari dasbor, paket dan versinya dipilih dari daftar — yang tidak ada
        // berarti daftarnya sudah basi, dan proses yang menunjuk paket hilang
        // baru ketahuan saat robot gagal menjalankannya.
        if (folderId != null && request.packageName() != null && request.packageVersion() != null
                && !packageRepository.exists(tenantId, request.packageName(), request.packageVersion())) {
            throw ApiException.badRequest("Paket '" + request.packageName() + "' versi "
                    + request.packageVersion() + " tidak ada.");
        }

        UUID existingFolderId = resolveProcessFolder(tenantId, request.name(), folderId);

        permissionChecker.requireSave(principal, Permissions.PROCESSES, existingFolderId != null);

        if (existingFolderId != null) {
            processRepository.update(tenantId, existingFolderId, request.name(), request.packageName(),
                    request.packageVersion(), request.environment(), request.description());
        } else {
            processRepository.insert(tenantId, request.name(), request.packageName(), request.packageVersion(),
                    request.environment() == null ? DEFAULT_ENVIRONMENT : request.environment(),
                    request.description(), folderId);
        }
    }

    /**
     * Pindahkan proses beserta pemicu dan riwayat pekerjaannya; lihat
     * {@link ProcessRepository#moveToFolder}.
     *
     * @param sourceFolderId folder ASAL — sama seperti di endpoint lain, folder
     *                       yang sedang dibuka. Kosong berarti proses bernama
     *                       itu di mana pun ia berada.
     * @param request        folder TUJUAN
     */
    @Transactional
    public void moveToFolder(OpenOrchestratorPrincipal principal, String name, String sourceFolderId,
                             MoveToFolderRequest request) {
        UUID tenantId = principal.tenantId();
        UUID requestedSource = folderAccessService.resolveFolderFilter(principal, sourceFolderId);
        UUID target = folderAccessService.resolveFolderFilter(principal, request.folderId());

        if (target == null) throw ApiException.badRequest("folderId wajib diisi.");

        UUID source = resolveProcessFolder(tenantId, name, requestedSource);

        if (source == null) throw processNotFound(name);
        if (source.equals(target)) return;

        // Diperiksa lebih dulu, bukan dibiarkan ditolak indeks unik: yang itu
        // hanya menghasilkan "terjadi kesalahan di server".
        if (processRepository.existsInFolder(tenantId, name, target)) {
            throw ApiException.conflict("Folder tujuan sudah punya proses bernama '" + name + "'.");
        }

        processRepository.findConflictingTriggerName(tenantId, name, source, target).ifPresent(trigger -> {
            throw ApiException.conflict("Folder tujuan sudah punya pemicu bernama '" + trigger + "'.");
        });

        processRepository.moveToFolder(tenantId, name, source, target);
    }

    /** Nama proses unik per folder: {@code folderId} menyebut yang mana; kosong berarti di mana pun ia berada. */
    public void delete(OpenOrchestratorPrincipal principal, String name, String folderId) {
        UUID tenantId = principal.tenantId();
        UUID folder = resolveProcessFolder(tenantId, name, folderAccessService.resolveFolderFilter(principal, folderId));

        if (folder == null || processRepository.delete(tenantId, name, folder) == 0) {
            throw processNotFound(name);
        }
    }

    private static ApiException processNotFound(String name) {
        return ApiException.notFound("Proses '" + name + "' tidak ada.");
    }
}
