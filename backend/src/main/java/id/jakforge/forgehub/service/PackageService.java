package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.config.ForgeHubProperties;
import id.jakforge.forgehub.dto.request.PublishPackageRequest;
import id.jakforge.forgehub.dto.response.PublishPackageResponse;
import id.jakforge.forgehub.model.FileContent;
import id.jakforge.forgehub.model.Severity;
import id.jakforge.forgehub.repository.AlertRepository;
import id.jakforge.forgehub.repository.LogRepository;
import id.jakforge.forgehub.repository.PackageRepository;
import id.jakforge.forgehub.repository.ProcessRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.PermissionChecker;
import id.jakforge.forgehub.security.Permissions;
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
    private static final String ALERT_SOURCE = "packages";
    private static final String UNKNOWN_PUBLISHER = "?";

    private final PackageRepository packageRepository;
    private final ProcessRepository processRepository;
    private final LogRepository logRepository;
    private final AlertRepository alertRepository;
    private final FolderAccessService folderAccessService;
    private final PermissionChecker permissionChecker;
    private final ForgeHubProperties properties;

    /** Tanpa {@code folderId}: seluruh umpan; selain itu paket yang dipakai proses di folder itu. */
    public List<Map<String, Object>> findAll(ForgeHubPrincipal principal, String folderId) {
        return packageRepository.findAll(principal.tenantId(),
                folderAccessService.resolveFolderFilter(principal, folderId));
    }

    /**
     * Terbitkan paket dari Studio.
     *
     * <p>packages.create untuk versi baru, packages.update untuk menerbitkan
     * ulang versi yang sudah ada.
     */
    @Transactional
    public PublishPackageResponse publish(ForgeHubPrincipal principal, PublishPackageRequest request) {
        if (request.name() == null) throw ApiException.badRequest("Nama paket wajib diisi.");

        UUID tenantId = principal.tenantId();
        String publisher = principal.username();

        boolean alreadyExists = packageRepository.exists(tenantId, request.name(), request.version());
        permissionChecker.requireSave(principal, Permissions.PACKAGES, alreadyExists);

        byte[] content = decodeContent(request.contentBase64());
        long sizeBytes = content == null ? 0 : content.length;

        if (alreadyExists) {
            packageRepository.update(tenantId, request.name(), request.version(), request.description(),
                    request.entryPoint(), publisher, sizeBytes, content);
        } else {
            packageRepository.insert(tenantId, request.name(), request.version(), request.description(),
                    request.entryPoint(), publisher, sizeBytes, content);
        }

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
    public FileContent getContent(ForgeHubPrincipal principal, String name, String version) {
        byte[] content = packageRepository.findContent(principal.tenantId(), name, version)
                .orElseThrow(() -> ApiException.notFound("Paket tidak ada atau tanpa isi."));

        return new FileContent(name + "." + version + PACKAGE_FILE_EXTENSION, PACKAGE_CONTENT_TYPE, content);
    }

    public void delete(ForgeHubPrincipal principal, String name, String version) {
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
