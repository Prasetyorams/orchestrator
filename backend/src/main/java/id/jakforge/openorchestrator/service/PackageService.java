package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Hashes;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.dto.request.PublishPackageRequest;
import id.jakforge.openorchestrator.dto.response.PublishPackageResponse;
import id.jakforge.openorchestrator.dto.response.UploadPackageResponse;
import id.jakforge.openorchestrator.model.FileContent;
import id.jakforge.openorchestrator.model.PackageSource;
import id.jakforge.openorchestrator.model.Severity;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.repository.PackageRepository;
import id.jakforge.openorchestrator.repository.ProcessRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.PermissionChecker;
import id.jakforge.openorchestrator.security.Permissions;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
    /** errorCode: versi itu masih disematkan proses atau dipegang robot (V17). */
    public static final String PACKAGE_IN_USE = "PACKAGE_IN_USE";
    static final String PROCESS_CREATED = "CREATED";
    static final String PROCESS_UPDATED = "UPDATED";
    static final String PROCESS_UNCHANGED = "UNCHANGED";
    private static final int MAX_FILE_NAME_LENGTH = 255;
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
        checkPublishable(principal, request);

        return store(principal, request, decodeContent(request.contentBase64()), null);
    }

    /**
     * Terbitkan paket yang diunggah sebagai berkas biner (multipart/form-data,
     * kemampuan {@code packages.upload.binary}). Aturannya sama dengan
     * {@link #publish}; bedanya isi tidak melewati base64 di dalam JSON, jadi
     * memori puncaknya kira-kira sebesar paketnya, bukan 2,3 kali.
     *
     * <p>Pemeriksaan izin dan versi DULU, baru isinya dibaca: unggahan yang
     * pasti ditolak tidak perlu dimuat ke memori.
     */
    @Transactional
    public PublishPackageResponse publishFile(OpenOrchestratorPrincipal principal, PublishPackageRequest request,
                                              MultipartFile file) {
        checkPublishable(principal, request);

        if (file == null || file.isEmpty()) throw ApiException.badRequest("Berkas paket (file) wajib diisi.");
        checkSize(file.getSize());

        try {
            return store(principal, request, file.getBytes(), fileNameOf(file));
        } catch (IOException e) {
            throw ApiException.badRequest("Berkas paket tidak bisa dibaca: " + e.getMessage());
        }
    }

    /**
     * Unggah paket manual dari dasbor (V17, packages.create): berkas .nupkg
     * atau .zip proyek yang sudah jadi, tanpa Studio.
     *
     * <p>Nama, versi, dan titik masuknya dibaca DARI PAKETNYA
     * ({@link PackageInspector}) — bukan diketik ulang — dan aturannya sama
     * dengan Terbitkan dari Studio: {@link PackageNaming}, versi yang sudah ada
     * ditolak 409, isi disimpan di baris yang sama dengan metadatanya.
     *
     * <p>Satu beda yang disengaja: proses hanya DIPINDAH ke versi yang lebih
     * baru. Studio selalu menerbitkan versi yang lebih tinggi; unggahan manual
     * bisa saja versi lama (arsip, pemulihan), dan itu tidak boleh diam-diam
     * menurunkan versi proses yang sedang berjalan.
     */
    @Transactional
    public UploadPackageResponse upload(OpenOrchestratorPrincipal principal, MultipartFile file) {
        permissionChecker.requireSave(principal, Permissions.PACKAGES, false);

        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Pilih berkas paket (.nupkg atau .zip) untuk diunggah.");
        }
        checkSize(file.getSize());

        byte[] original;
        try {
            original = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Berkas paket tidak bisa dibaca: " + e.getMessage());
        }

        String fileName = fileNameOf(file);
        PackageInspector.Inspection inspected = PackageInspector.inspect(original, fileName);
        checkSize(inspected.content().length);

        UUID tenantId = principal.tenantId();
        String name = inspected.name();
        String version = inspected.version();
        if (packageRepository.exists(tenantId, name, version)) throw versionExists(name, version);

        String sha256 = Hashes.sha256Hex(inspected.content());
        insertPackage(tenantId, name, version, inspected.description(), inspected.entryPoint(), principal.username(),
                inspected.content(), sha256, PackageSource.MANUAL_UPLOAD, fileName, Hashes.sha256Hex(original));

        String processChange = linkUploadedVersion(tenantId, name, version, inspected.description());

        String publisher = principal.username() == null ? UNKNOWN_PUBLISHER : principal.username();
        alertRepository.insert(tenantId, Severity.Info, "Paket diunggah",
                name + " " + version + " diunggah manual oleh " + publisher + " (" + fileName + ").", ALERT_SOURCE);
        logRepository.insertSystemEntry(tenantId,
                "Paket " + name + " " + version + " diunggah manual dari berkas " + fileName + ".", name, null);

        return new UploadPackageResponse(true, name, version, inspected.content().length, sha256,
                inspected.entryPoint(), PackageSource.MANUAL_UPLOAD.name(), inspected.layout().name(),
                inspected.converted(), name, processChange, inspected.dependencies(), inspected.warnings());
    }

    /** Proses bernama sama: dibuat kalau belum ada, dipindah hanya kalau versinya lebih lama. */
    private String linkUploadedVersion(UUID tenantId, String name, String version, String description) {
        List<Map<String, Object>> processes = processRepository.findPackageLinks(tenantId, name);

        if (processes.isEmpty()) {
            processRepository.insert(tenantId, name, name, version, PublishPackageRequest.DEFAULT_ENVIRONMENT,
                    description, null);
            return PROCESS_CREATED;
        }

        boolean moved = false;
        for (Map<String, Object> process : processes) {
            String current = (String) process.get("packageVersion");
            if (current == null || !PackageNaming.isValidVersion(current) || PackageNaming.compare(version, current) > 0) {
                processRepository.linkPackageById(tenantId, Uuids.parseOrNull(String.valueOf(process.get("id"))),
                        name, version);
                moved = true;
            }
        }

        return moved ? PROCESS_UPDATED : PROCESS_UNCHANGED;
    }

    /** Nama berkas saja, tanpa folder yang dikirim sebagian peramban lama. */
    private static String fileNameOf(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) return null;

        String name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1).trim();
        return name.length() > MAX_FILE_NAME_LENGTH ? name.substring(0, MAX_FILE_NAME_LENGTH) : name;
    }

    private void checkPublishable(OpenOrchestratorPrincipal principal, PublishPackageRequest request) {
        if (request.name() == null) throw ApiException.badRequest("Nama paket wajib diisi.");
        PackageNaming.validate(request.name(), request.version());

        permissionChecker.requireSave(principal, Permissions.PACKAGES, false);

        if (packageRepository.exists(principal.tenantId(), request.name(), request.version())) {
            throw versionExists(request.name(), request.version());
        }
    }

    /** Satu kebijakan versi ganda untuk Studio dan unggah manual: isi sebuah versi tidak pernah berubah. */
    private static ApiException versionExists(String name, String version) {
        return ApiException.conflict("Paket '" + name + "' versi " + version
                + " sudah ada. Naikkan versinya, lalu terbitkan lagi.").withCode(PACKAGE_VERSION_EXISTS);
    }

    /**
     * Baris paket — metadata dan isinya sekaligus. Dua penerbitan versi yang
     * sama pada saat bersamaan sama-sama lolos {@code exists}; yang kalah
     * ditolak oleh kunci unik dan mendapat 409 yang sama, bukan 500.
     */
    private void insertPackage(UUID tenantId, String name, String version, String description, String entryPoint,
                               String publisher, byte[] content, String sha256, PackageSource source,
                               String sourceFileName, String sourceFileSha256) {
        try {
            packageRepository.insert(tenantId, name, version, description, entryPoint, publisher,
                    content == null ? 0 : content.length, content, sha256, source, sourceFileName, sourceFileSha256);
        } catch (DuplicateKeyException e) {
            throw versionExists(name, version);
        }
    }

    private PublishPackageResponse store(OpenOrchestratorPrincipal principal, PublishPackageRequest request,
                                         byte[] content, String sourceFileName) {
        UUID tenantId = principal.tenantId();
        String publisher = principal.username();

        long sizeBytes = content == null ? 0 : content.length;

        // Sidik isinya: robot memeriksa unduhannya terhadap nilai ini sebelum
        // menjalankan apa pun dari dalamnya.
        String sha256 = content == null ? null : Hashes.sha256Hex(content);

        insertPackage(tenantId, request.name(), request.version(), request.description(), request.entryPoint(),
                publisher, content, sha256, PackageSource.STUDIO, sourceFileName, sourceFileName == null ? null : sha256);

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

    /**
     * Hapus satu versi — kecuali yang masih dipakai: disematkan proses
     * (job berikutnya akan memintanya) atau sedang dipegang robot (robot yang
     * mengunduh ulang, mis. sesudah cache-nya dibersihkan, akan mendapat 404).
     * Penolakannya {@code 409 PACKAGE_IN_USE}, menyebut siapa pemakainya.
     */
    @Transactional
    public void delete(OpenOrchestratorPrincipal principal, String name, String version) {
        UUID tenantId = principal.tenantId();

        List<String> processes = packageRepository.findProcessesUsing(tenantId, name, version);
        if (!processes.isEmpty()) {
            String shown = String.join(", ", processes.subList(0, Math.min(5, processes.size())))
                    + (processes.size() > 5 ? ", dan " + (processes.size() - 5) + " lainnya" : "");
            throw ApiException.conflict("Paket '" + name + "' versi " + version + " masih dipakai proses: " + shown
                    + ". Pindahkan prosesnya ke versi lain dulu, lalu hapus lagi.").withCode(PACKAGE_IN_USE);
        }

        int jobs = packageRepository.countHeldJobsUsing(tenantId, name, version);
        if (jobs > 0) {
            throw ApiException.conflict("Paket '" + name + "' versi " + version + " sedang dijalankan " + jobs
                    + " pekerjaan. Tunggu sampai selesai, lalu hapus lagi.").withCode(PACKAGE_IN_USE);
        }

        if (packageRepository.delete(tenantId, name, version) == 0) {
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

        checkSize(content.length);

        return content;
    }

    private void checkSize(long sizeBytes) {
        DataSize maxSize = properties.limits().maxPackageSize();

        if (sizeBytes > maxSize.toBytes()) {
            throw ApiException.badRequest("Paket terlalu besar. Batasnya " + maxSize.toMegabytes() + " MB.");
        }
    }
}
