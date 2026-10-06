package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Hashes;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.dto.request.PublishPackageRequest;
import id.jakforge.openorchestrator.dto.response.PublishPackageResponse;
import id.jakforge.openorchestrator.model.FileContent;
import id.jakforge.openorchestrator.model.Severity;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.repository.PackageRepository;
import id.jakforge.openorchestrator.repository.ProcessRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.PermissionChecker;
import id.jakforge.openorchestrator.security.Permissions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aturan tentang paket.
 *
 * <p>Paket dan proses satu hal di mata pemakainya: menerbitkan paket dari
 * Studio membuat prosesnya sekaligus. Aturan itu tinggal di sini, bukan di
 * repository mana pun.
 */
@Service
@RequiredArgsConstructor
public class PackageService {

    static final String PACKAGE_CONTENT_TYPE = "application/zip";
    static final String PACKAGE_FILE_EXTENSION = ".zip";
    /** errorCode: versi paket itu sudah pernah terbit. */
    public static final String PACKAGE_VERSION_EXISTS = "PACKAGE_VERSION_EXISTS";
    private static final String ALERT_SOURCE = "packages";
    private static final String UNKNOWN_PUBLISHER = "?";

    private final PackageRepository packageRepository;
    private final ProcessRepository processRepository;
    private final LogRepository logRepository;
    private final AlertRepository alertRepository;
    private final FolderAccessService folderAccessService;
    private final PermissionChecker permissionChecker;
    private final OpenOrchestratorProperties properties;

    /** Tanpa {@code folderId}: seluruh umpan; selain itu paket yang dipakai proses di folder itu. */
    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal, String folderId) {
        return packageRepository.findAll(principal.tenantId(),
                folderAccessService.resolveFolderFilter(principal, folderId));
    }

    /**
     * Terbitkan paket dari Studio (packages.create).
     *
     * <p>Versi yang SUDAH ADA ditolak {@code 409 PACKAGE_VERSION_EXISTS},
     * seperti di UiPath: isi sebuah versi tidak pernah berubah sesudah terbit.
     * Job yang sudah menyebut versi itu — beserta sidik SHA-256-nya, yang
     * diperiksa robot — harus mendapat isi yang sama; perubahan diterbitkan
     * sebagai versi baru.
     */
    @Transactional
    public PublishPackageResponse publish(OpenOrchestratorPrincipal principal, PublishPackageRequest request) {
        if (request.name() == null) throw ApiException.badRequest("Nama paket wajib diisi.");

        UUID tenantId = principal.tenantId();
        String publisher = principal.username();

        permissionChecker.requireSave(principal, Permissions.PACKAGES, false);

        if (packageRepository.exists(tenantId, request.name(), request.version())) {
            throw ApiException.conflict("Paket '" + request.name() + "' versi " + request.version()
                    + " sudah ada. Naikkan versinya, lalu terbitkan lagi.").withCode(PACKAGE_VERSION_EXISTS);
        }

        byte[] content = decodeContent(request.contentBase64());
        long sizeBytes = content == null ? 0 : content.length;

        // Sidik isinya: robot memeriksa unduhannya terhadap nilai ini sebelum
        // menjalankan apa pun dari dalamnya.
        String sha256 = content == null ? null : Hashes.sha256Hex(content);

        packageRepository.insert(tenantId, request.name(), request.version(), request.description(),
                request.entryPoint(), publisher, sizeBytes, content, sha256);

        // Menerbitkan paket hampir selalu berarti ingin proses dengan nama yang
        // sama tersedia untuk dijalankan. Membuatnya di sini menghemat satu
        // langkah yang mudah terlupa — dan yang terlupa itu baru terasa saat
        // pekerjaan ditolak dengan "proses belum diterbitkan".
        if (processRepository.existsByName(tenantId, request.name())) {
            processRepository.linkPackage(tenantId, request.name(), request.name(), request.version());
        } else {
            // Folder bawaan: Studio belum tahu tentang folder, dan proses yang
            // baru terbit harus langsung terlihat di tempat yang sama bagi semua.
            processRepository.insert(tenantId, request.name(), request.name(), request.version(),
                    request.environment(), request.description(), null);
        }

        alertRepository.insert(tenantId, Severity.Info, "Paket diterbitkan",
                request.name() + " " + request.version() + " diterbitkan oleh "
                        + (publisher == null ? UNKNOWN_PUBLISHER : publisher) + ".", ALERT_SOURCE);

        logRepository.insertSystemEntry(tenantId,
                "Paket " + request.name() + " " + request.version() + " diterbitkan.", request.name(), null);

        return new PublishPackageResponse(true, request.name(), request.version(), sizeBytes);
    }

    /** Isi paket sebagai .zip, untuk diunduh. */
    public FileContent getContent(OpenOrchestratorPrincipal principal, String name, String version) {
        byte[] content = packageRepository.findContent(principal.tenantId(), name, version)
                .orElseThrow(() -> ApiException.notFound("Paket tidak ada atau tanpa isi."));

        return new FileContent(name + "." + version + PACKAGE_FILE_EXTENSION, PACKAGE_CONTENT_TYPE, content);
    }

    public void delete(OpenOrchestratorPrincipal principal, String name, String version) {
        if (packageRepository.delete(principal.tenantId(), name, version) == 0) {
            throw ApiException.notFound("Paket tidak ada.");
        }
    }

    /**
     * Isi paket dari base64, dengan batas ukuran.
     *
     * <p>Isinya dikirim sebagai base64 DI DALAM badan JSON: paket 64 MB menjadi
     * sekitar 85 MB teks yang harus muat di memori sekaligus. Tanpa batas, satu
     * penerbitan yang keliru menjatuhkan layanan untuk semua orang.
     */
    private byte[] decodeContent(String base64) {
        if (base64 == null || base64.isEmpty()) return null;

        byte[] content;
        try {
            content = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("contentBase64 bukan base64 yang sah.");
        }

        DataSize maxSize = properties.limits().maxPackageSize();

        if (content.length > maxSize.toBytes()) {
            throw ApiException.badRequest("Paket terlalu besar. Batasnya " + maxSize.toMegabytes() + " MB.");
        }

        return content;
    }
}
