package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.dto.request.CreateBucketRequest;
import id.jakforge.openorchestrator.dto.request.MoveToFolderRequest;
import id.jakforge.openorchestrator.dto.request.UploadFileRequest;
import id.jakforge.openorchestrator.dto.response.UploadFileResponse;
import id.jakforge.openorchestrator.model.FileContent;
import id.jakforge.openorchestrator.repository.BucketRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Aturan tentang ember penyimpanan (gudang berkas) dan berkasnya. */
@Service
@RequiredArgsConstructor
public class BucketService {

    private static final String FILE_NOT_FOUND = "Berkas tidak ada.";

    private final BucketRepository bucketRepository;
    private final FolderAccessService folderAccessService;
    private final OpenOrchestratorProperties properties;

    /** Tanpa {@code folderId}: ember seluruh penyewa. */
    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal, String folderId) {
        return bucketRepository.findAll(principal.tenantId(),
                folderAccessService.resolveFolderFilter(principal, folderId));
    }

    /** {@code folderId} kosong berarti folder bawaan. */
    @Transactional
    public void create(OpenOrchestratorPrincipal principal, CreateBucketRequest request) {
        UUID tenantId = principal.tenantId();
        UUID folderId = folderAccessService.resolveFolderFilter(principal, request.folderId());

        if (bucketRepository.existsByName(tenantId, request.name())) {
            throw ApiException.conflict("Ember '" + request.name() + "' sudah ada.");
        }

        bucketRepository.insert(tenantId, request.name(), request.description(), folderId);
    }

    public void moveToFolder(OpenOrchestratorPrincipal principal, String name, MoveToFolderRequest request) {
        UUID folderId = folderAccessService.resolveFolderFilter(principal, request.folderId());

        if (folderId == null) throw ApiException.badRequest("folderId wajib diisi.");

        if (bucketRepository.moveToFolder(principal.tenantId(), name, folderId) == 0) {
            throw ApiException.notFound("Ember '" + name + "' tidak ada.");
        }
    }

    @Transactional
    public void delete(OpenOrchestratorPrincipal principal, String name) {
        if (bucketRepository.deleteWithFiles(principal.tenantId(), name) == 0) {
            throw ApiException.notFound("Ember tidak ada.");
        }
    }

    public List<Map<String, Object>> findFiles(OpenOrchestratorPrincipal principal, String bucketName) {
        return bucketRepository.findFiles(principal.tenantId(), bucketName);
    }

    /**
     * Unggah berkas.
     *
     * <p>Batas ukurannya sama alasannya dengan batas paket: isinya lewat base64
     * di dalam JSON.
     */
    @Transactional
    public UploadFileResponse upload(OpenOrchestratorPrincipal principal, String bucketName, UploadFileRequest request) {
        UUID tenantId = principal.tenantId();
        String fileName = sanitizeFileName(request.fileName());

        if (fileName == null) throw ApiException.badRequest("fileName wajib diisi dan sah.");

        byte[] content;
        try {
            content = Base64.getDecoder().decode(request.contentBase64());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("contentBase64 bukan base64 yang sah.");
        }

        DataSize maxSize = properties.limits().maxBucketFileSize();

        if (content.length > maxSize.toBytes()) {
            throw ApiException.badRequest("Berkas terlalu besar. Batasnya " + maxSize.toMegabytes() + " MB.");
        }

        if (!bucketRepository.existsByName(tenantId, bucketName)) {
            throw ApiException.notFound("Ember '" + bucketName + "' tidak ada.");
        }

        // Berkas dengan nama yang sama DIGANTI, bukan ditumpuk. Gudang berisi
        // lima "laporan.xlsx" dengan waktu unggah berbeda tidak menolong siapa
        // pun yang mencari laporan hari ini.
        bucketRepository.deleteFileByName(tenantId, bucketName, fileName);
        bucketRepository.insertFile(tenantId, bucketName, fileName, request.contentType(), content.length,
                principal.username(), content);

        return new UploadFileResponse(true, fileName, content.length);
    }

    public FileContent getFileContent(OpenOrchestratorPrincipal principal, String bucketName, String fileIdText) {
        UUID fileId = Uuids.parseOrNull(fileIdText);

        FileContent file = fileId == null
                ? null
                : bucketRepository.findFileContent(principal.tenantId(), bucketName, fileId).orElse(null);

        if (file == null || file.content().length == 0) throw ApiException.notFound(FILE_NOT_FOUND);

        return file;
    }

    public void deleteFile(OpenOrchestratorPrincipal principal, String bucketName, String fileIdText) {
        UUID fileId = Uuids.parseOrNull(fileIdText);

        if (fileId == null || bucketRepository.deleteFile(principal.tenantId(), bucketName, fileId) == 0) {
            throw ApiException.notFound(FILE_NOT_FOUND);
        }
    }

    /**
     * Buang segala yang bisa mengubah tempat berkas mendarat.
     *
     * <p>Nama berkas datang dari luar dan dipakai sebagai nama unduhan. Pemisah
     * jalur dibuang supaya tidak ada yang bisa menulis {@code ../} ke dalamnya
     * dan menaruh berkas di tempat lain pada komputer orang yang mengunduhnya.
     */
    static String sanitizeFileName(String fileName) {
        if (fileName == null) return null;

        String sanitized = fileName.replace('\\', '/');
        int lastSeparator = sanitized.lastIndexOf('/');

        if (lastSeparator >= 0) sanitized = sanitized.substring(lastSeparator + 1);

        sanitized = sanitized.trim();

        // "." dan ".." tidak menyisakan apa pun sesudah pemisahnya dibuang, dan
        // keduanya bukan nama berkas.
        if (sanitized.isEmpty() || ".".equals(sanitized) || "..".equals(sanitized)) return null;

        return sanitized;
    }
}
