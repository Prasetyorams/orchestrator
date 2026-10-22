package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Hashes;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.dto.response.UploadLibraryResponse;
import id.jakforge.openorchestrator.model.FileContent;
import id.jakforge.openorchestrator.model.Severity;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.LibraryRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.repository.PackageRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Feed library (V20) — padanan feed Libraries di UiPath Orchestrator.
 *
 * <p>Library adalah kode yang dijalankan robot, jadi diperlakukan seperti
 * paket proses: izinnya izin paket (unggah = packages.create, lihat dan
 * unduh = packages.read, hapus = packages.delete), setiap unggah dan hapus
 * masuk audit, isi sebuah versi tidak pernah berubah sesudah diunggah, dan
 * robot mencocokkan sidik SHA-256-nya sebelum memakai DLL-nya.
 *
 * <p>Satu feed untuk seluruh penyewa, seperti umpan paket proses.
 */
@Service
@RequiredArgsConstructor
public class LibraryService {

    /** errorCode: versi library itu sudah ada. */
    public static final String LIBRARY_VERSION_EXISTS = "LIBRARY_VERSION_EXISTS";
    /** errorCode: versi itu masih dibutuhkan proses atau sedang dipakai robot. */
    public static final String LIBRARY_IN_USE = "LIBRARY_IN_USE";
    static final String FILE_EXTENSION = ".nupkg";
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final String ALERT_SOURCE = "libraries";
    private static final String UNKNOWN_PUBLISHER = "?";

    private final LibraryRepository libraryRepository;
    private final PackageRepository packageRepository;
    private final AlertRepository alertRepository;
    private final LogRepository logRepository;
    private final PermissionChecker permissionChecker;
    private final OpenOrchestratorProperties properties;

    // ----------------------------------------------------------------- daftar

    /** Satu baris per versi: per Id, lalu versi tertinggi lebih dulu. */
    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal, String search) {
        List<Map<String, Object>> rows = new ArrayList<>(libraryRepository.findAll(principal.tenantId(), search));

        rows.sort(Comparator.<Map<String, Object>, String>comparing(r -> ((String) r.get("name")).toLowerCase(Locale.ROOT))
                .thenComparing((a, b) -> PackageNaming.compare((String) b.get("version"), (String) a.get("version"))));

        return rows.stream().map(LibraryService::toApi).toList();
    }

    /** Bentuk di API: {@code id} adalah Id library (NuGet), bukan kunci baris. */
    private static Map<String, Object> toApi(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("name"));
        result.put("version", row.get("version"));
        result.put("description", row.get("description"));
        result.put("authors", row.get("authors"));
        result.put("assemblyFile", row.get("assemblyFile"));
        result.put("dependencies", Dependencies.fromJson(row.get("dependencies")));
        result.put("publishedBy", row.get("publishedBy"));
        result.put("publishedAt", row.get("publishedAt"));
        result.put("sizeBytes", row.get("sizeBytes"));
        result.put("sha256", row.get("sha256"));
        result.put("sourceFileName", row.get("sourceFileName"));
        return result;
    }

    // ----------------------------------------------------------------- unggah

    /**
     * Unggah library (.nupkg hasil Terbitkan proyek Library di OpenStudio).
     * Id dan versinya dibaca dari .nuspec; versi yang sudah ada ditolak
     * {@code 409 LIBRARY_VERSION_EXISTS} — job yang sudah diberi versi itu,
     * beserta sidiknya, harus tetap mendapat isi yang sama.
     */
    @Transactional
    public UploadLibraryResponse upload(OpenOrchestratorPrincipal principal, MultipartFile file) {
        permissionChecker.requireSave(principal, Permissions.PACKAGES, false);

        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Pilih berkas library (.nupkg) untuk diunggah.");
        }
        checkSize(file.getSize());

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Berkas library tidak bisa dibaca: " + e.getMessage());
        }

        String fileName = fileNameOf(file);
        LibraryInspector.Inspection inspected = LibraryInspector.inspect(content, fileName);

        UUID tenantId = principal.tenantId();
        String name = inspected.name();
        String version = inspected.version();

        List<Map<String, Object>> existing = libraryRepository.findVersions(tenantId, name);
        if (existing.stream().anyMatch(r -> PackageNaming.compare((String) r.get("version"), version) == 0)) {
            throw versionExists(name, version);
        }

        // Dependensinya sendiri: library di feed dipasang robot bersama library ini;
        // yang lain activity yang harus sudah ada di mesin robot.
        List<String> warnings = new ArrayList<>(inspected.warnings());
        Function<String, List<LibraryResolver.Available>> feed = cached(feed(libraryRepository, tenantId));
        List<String> problems = LibraryResolver.resolve(inspected.dependencies(), feed).problems();
        if (!problems.isEmpty()) {
            warnings.addAll(problems);
            warnings.add("Job yang memakai library ini gagal saat diambil robot sampai dependensinya ada di feed library.");
        }
        List<String> activities = inspected.dependencies().entrySet().stream()
                .filter(d -> feed.apply(d.getKey()).isEmpty())
                .map(d -> (d.getKey() + " " + d.getValue()).trim()).toList();
        if (!activities.isEmpty()) {
            warnings.add("Library ini membutuhkan activity: " + String.join(", ", activities)
                    + ". Robot tidak memasangnya otomatis; pastikan sudah ada di mesin robot.");
        }

        String sha256 = Hashes.sha256Hex(content);
        try {
            libraryRepository.insert(tenantId, name, version, inspected.description(), inspected.authors(),
                    inspected.assemblyFile(), Dependencies.toJson(inspected.dependencies()), principal.username(),
                    content, sha256, fileName);
        } catch (DuplicateKeyException e) {
            throw versionExists(name, version);
        }

        String publisher = principal.username() == null ? UNKNOWN_PUBLISHER : principal.username();
        alertRepository.insert(tenantId, Severity.Info, "Library diunggah",
                name + " " + version + " diunggah oleh " + publisher + ".", ALERT_SOURCE);
        logRepository.insertSystemEntry(tenantId, "Library " + name + " " + version + " diunggah"
                + (fileName == null ? "." : " dari berkas " + fileName + "."), null, null);

        return new UploadLibraryResponse(true, name, version, inspected.description(), content.length, sha256,
                inspected.assemblyFile(), inspected.dependencies(), List.copyOf(warnings));
    }

    private static ApiException versionExists(String name, String version) {
        return ApiException.conflict("Library '" + name + "' versi " + version
                + " sudah ada. Naikkan versinya, lalu terbitkan lagi.").withCode(LIBRARY_VERSION_EXISTS);
    }

    private void checkSize(long sizeBytes) {
        DataSize maxSize = properties.limits().maxPackageSize();

        if (sizeBytes > maxSize.toBytes()) {
            throw ApiException.badRequest("Library terlalu besar. Batasnya " + maxSize.toMegabytes() + " MB.");
        }
    }

    private static String fileNameOf(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) return null;

        String name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1).trim();
        return name.length() > MAX_FILE_NAME_LENGTH ? name.substring(0, MAX_FILE_NAME_LENGTH) : name;
    }

    // ----------------------------------------------------------------- unduh

    /** Isi .nupkg, untuk Studio, dasbor, dan robot. */
    public FileContent getContent(OpenOrchestratorPrincipal principal, String name, String version) {
        LibraryRepository.Content content = libraryRepository.findContent(principal.tenantId(), name, version)
                .orElseThrow(() -> ApiException.notFound("Library tidak ada."));

        return new FileContent(content.name() + "." + content.version() + FILE_EXTENSION,
                FileContent.DEFAULT_CONTENT_TYPE, content.bytes());
    }

    /**
     * Robot Agent hanya mengunduh library yang diberikan ke job yang sedang
     * dipegang mesinnya — sama dengan aturan unduhan paket proses.
     */
    public FileContent getContentForAgent(OpenOrchestratorPrincipal agent, String name, String version) {
        if (!canAgentDownload(agent, name, version)) {
            throw ApiException.forbidden("Library ini tidak dipakai job yang sedang dijalankan robot di mesin ini.");
        }

        return getContent(agent, name, version);
    }

    public boolean canAgentDownload(OpenOrchestratorPrincipal agent, String name, String version) {
        return name != null && version != null && agent.machineId() != null
                && libraryRepository.machineRunsLibrary(agent.machineId(), name, version);
    }

    // ----------------------------------------------------------------- hapus

    /**
     * Hapus satu versi — kecuali yang masih dibutuhkan: diberikan ke job yang
     * sedang dipegang robot, atau dibutuhkan paket yang disematkan proses
     * (job berikutnya akan memintanya). Penolakannya {@code 409 LIBRARY_IN_USE}.
     */
    @Transactional
    public void delete(OpenOrchestratorPrincipal principal, String name, String version) {
        UUID tenantId = principal.tenantId();

        String shownName = libraryRepository.findVersions(tenantId, name).stream()
                .filter(r -> version.equals(r.get("version"))).map(r -> (String) r.get("name")).findFirst()
                .orElseThrow(() -> ApiException.notFound("Library tidak ada."));

        int jobs = libraryRepository.countHeldJobsUsing(tenantId, name, version);
        if (jobs > 0) {
            throw ApiException.conflict("Library '" + shownName + "' versi " + version + " sedang dipakai " + jobs
                    + " pekerjaan. Tunggu sampai selesai, lalu hapus lagi.").withCode(LIBRARY_IN_USE);
        }

        List<String> processes = new ArrayList<>();
        Function<String, List<LibraryResolver.Available>> feed = cached(feed(libraryRepository, tenantId));
        for (Map<String, Object> pinned : packageRepository.findPinnedVersions(tenantId)) {
            String packageName = (String) pinned.get("name");
            String packageVersion = (String) pinned.get("version");
            PackageInspector.ProjectDependencies dependencies = storedOrRead(pinned, tenantId, packageName, packageVersion);

            boolean needed = LibraryResolver.resolve(dependencies.dependencies(), dependencies.libraries(), feed)
                    .libraries().stream()
                    .anyMatch(l -> l.name().equalsIgnoreCase(name) && l.version().equals(version));
            if (needed) processes.addAll(packageRepository.findProcessesUsing(tenantId, packageName, packageVersion));
        }

        if (!processes.isEmpty()) {
            String shown = String.join(", ", processes.subList(0, Math.min(5, processes.size())))
                    + (processes.size() > 5 ? ", dan " + (processes.size() - 5) + " lainnya" : "");
            throw ApiException.conflict("Library '" + shownName + "' versi " + version + " masih dibutuhkan proses: "
                    + shown + ". Pindahkan prosesnya ke paket yang tidak memakainya dulu, lalu hapus lagi.")
                    .withCode(LIBRARY_IN_USE);
        }

        libraryRepository.delete(tenantId, name, version);
    }

    // ----------------------------------------------------------------- untuk job

    /** Library yang dibutuhkan satu versi paket proses; kosong kalau paketnya tidak ada atau tanpa library. */
    public LibraryResolver.Resolution resolveForPackage(UUID tenantId, String packageName, String packageVersion) {
        if (packageName == null || packageVersion == null) return LibraryResolver.Resolution.none();

        Optional<Map<String, Object>> row = packageRepository.findDependencies(tenantId, packageName, packageVersion);
        if (row.isEmpty()) return LibraryResolver.Resolution.none();

        PackageInspector.ProjectDependencies dependencies = storedOrRead(row.get(), tenantId, packageName, packageVersion);

        return LibraryResolver.resolve(dependencies.dependencies(), dependencies.libraries(),
                feed(libraryRepository, tenantId));
    }

    /** Diberikan ke job saat diambil: yang boleh diunduh robotnya, dan yang tidak boleh dihapus selama job itu jalan. */
    public void recordForJob(UUID tenantId, UUID jobId, List<LibraryResolver.Available> libraries) {
        for (LibraryResolver.Available library : libraries) {
            libraryRepository.recordForJob(tenantId, jobId, library.name(), library.version(), library.sha256());
        }
    }

    /**
     * Dependensi dan penanda library yang tersimpan; paket yang belum pernah
     * dibaca (dari sebelum V20, atau dikosongkan V21) dibaca sekali dari
     * isinya, lalu disimpan.
     */
    private PackageInspector.ProjectDependencies storedOrRead(Map<String, Object> row, UUID tenantId,
                                                              String packageName, String packageVersion) {
        if (row.get("dependencies") != null) {
            return new PackageInspector.ProjectDependencies(Dependencies.fromJson(row.get("dependencies")),
                    Dependencies.listFromJson(row.get("libraries")));
        }

        PackageInspector.ProjectDependencies read = PackageInspector.projectDependencies(
                packageRepository.findContent(tenantId, packageName, packageVersion).orElse(null));
        packageRepository.setDependencies(tenantId, packageName, packageVersion, Dependencies.toJson(read.dependencies()),
                Dependencies.listToJson(read.libraries()));
        return read;
    }

    /** Satu kueri per Id selama satu permintaan, walau Id-nya ditanyakan berkali-kali. */
    static Function<String, List<LibraryResolver.Available>> cached(Function<String, List<LibraryResolver.Available>> feed) {
        Map<String, List<LibraryResolver.Available>> seen = new java.util.HashMap<>();
        return name -> seen.computeIfAbsent(name.toLowerCase(Locale.ROOT), feed);
    }

    /** Versi di feed untuk satu Id — bentuk yang dibaca {@link LibraryResolver}. */
    static Function<String, List<LibraryResolver.Available>> feed(LibraryRepository repository, UUID tenantId) {
        return name -> repository.findVersions(tenantId, name).stream()
                .map(r -> new LibraryResolver.Available((String) r.get("name"), (String) r.get("version"),
                        (String) r.get("sha256"), ((Number) r.get("sizeBytes")).longValue(),
                        Dependencies.fromJson(r.get("dependencies"))))
                .toList();
    }
}
