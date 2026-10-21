package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Hashes;
import id.jakforge.openorchestrator.dto.request.PublishPackageRequest;
import id.jakforge.openorchestrator.dto.response.PublishPackageResponse;
import id.jakforge.openorchestrator.dto.response.UploadPackageResponse;
import id.jakforge.openorchestrator.model.PackageSource;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.LibraryRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.repository.PackageRepository;
import id.jakforge.openorchestrator.repository.ProcessRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.PermissionChecker;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import id.jakforge.openorchestrator.support.TestFolderAccess;
import id.jakforge.openorchestrator.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Menerbitkan paket: versi yang sudah ada ditolak seperti di UiPath. */
class PackageServiceTest {

    private final RecordingDatabase database = new RecordingDatabase();
    private final OpenOrchestratorPrincipal principal =
            new OpenOrchestratorPrincipal(UUID.randomUUID(), UUID.randomUUID(), "fajar", "Automation Developer");
    private final List<String> checkedPermissions = new ArrayList<>();

    private PackageService service(boolean versionExists, boolean allowed) {
        PackageRepository packages = new PackageRepository(database) {
            @Override
            public boolean exists(UUID tenantId, String name, String version) {
                return versionExists;
            }
        };

        PermissionChecker checker = (p, permission) -> {
            checkedPermissions.add(permission);
            return allowed;
        };

        return new PackageService(packages, new ProcessRepository(database), new LogRepository(database),
                new AlertRepository(database), TestFolderAccess.permitAll(), checker, TestProperties.defaults(), new LibraryRepository(database));
    }

    private static PublishPackageRequest request(String version) {
        return PublishPackageRequest.fromBody(Map.of("name", "Tagihan", "version", version, "entryPoint", "Main.xaml",
                "description", "Perbaikan pembulatan", "contentBase64",
                Base64.getEncoder().encodeToString("PK isi".getBytes())));
    }

    @Test
    @DisplayName("versi yang sudah ada: 409 PACKAGE_VERSION_EXISTS, isinya tidak ditimpa")
    void existingVersionRejected() {
        ApiException error = assertThrows(ApiException.class, () -> service(true, true).publish(principal, request("1.0.3")));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("PACKAGE_VERSION_EXISTS", error.errorCode());
        assertEquals("Paket 'Tagihan' versi 1.0.3 sudah ada. Naikkan versinya, lalu terbitkan lagi.", error.getMessage());
        assertTrue(database.statementsContaining("UPDATE packages").isEmpty());
        assertTrue(database.statementsContaining("INSERT INTO packages").isEmpty());
    }

    @Test
    @DisplayName("versi baru: disimpan; yang dibutuhkan hanya packages.create")
    void newVersionInserted() {
        service(false, true).publish(principal, request("1.0.4"));

        assertEquals(1, database.statementsContaining("INSERT INTO packages").size());
        assertEquals(List.of("packages.create"), checkedPermissions);
    }

    @Test
    @DisplayName("tanpa packages.create: 403 — sebelum menyebut apakah versinya ada")
    void permissionFirst() {
        ApiException error = assertThrows(ApiException.class, () -> service(true, false).publish(principal, request("1.0.3")));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
    }

    // ----------------------------------------------- unggah biner (packages.upload.binary)

    private static PublishPackageRequest form(String version) {
        return PublishPackageRequest.fromForm(Map.of("name", "Tagihan", "version", version, "entryPoint", "Main.xaml"));
    }

    @Test
    @DisplayName("biner: disimpan apa adanya, dengan ukuran dan SHA-256 dari isi berkasnya")
    void binaryUploadStored() {
        byte[] isi = "PK isi biner \u0000\u0001".getBytes();
        PublishPackageResponse response = service(false, true)
                .publishFile(principal, form("2.0.0"), new MockMultipartFile("file", "Tagihan.2.0.0.nupkg", null, isi));

        assertEquals(isi.length, response.sizeBytes());
        List<Object> args = database.argumentsOf("INSERT INTO packages");
        assertTrue(args.contains(isi));
        assertTrue(args.contains(Hashes.sha256Hex(isi)));
        assertEquals(List.of("packages.create"), checkedPermissions);
    }

    @Test
    @DisplayName("biner: versi yang sudah ada ditolak 409 sebelum berkasnya dibaca")
    void binaryExistingVersionRejectedBeforeReading() {
        MultipartFile unreadable = new MockMultipartFile("file", new byte[]{1}) {
            @Override
            public byte[] getBytes() {
                throw new AssertionError("isi berkas tidak boleh dibaca");
            }
        };

        ApiException error = assertThrows(ApiException.class,
                () -> service(true, true).publishFile(principal, form("1.0.3"), unreadable));

        assertEquals("PACKAGE_VERSION_EXISTS", error.errorCode());
    }

    @Test
    @DisplayName("biner: lebih besar dari batas ditolak 400 dari ukurannya, tanpa dibaca")
    void binaryTooLargeRejected() {
        MultipartFile huge = new MockMultipartFile("file", new byte[]{1}) {
            @Override
            public long getSize() {
                return 65L * 1024 * 1024;
            }

            @Override
            public byte[] getBytes() {
                throw new AssertionError("isi berkas tidak boleh dibaca");
            }
        };

        ApiException error = assertThrows(ApiException.class,
                () -> service(false, true).publishFile(principal, form("2.0.0"), huge));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertEquals("Paket terlalu besar. Batasnya 64 MB.", error.getMessage());
    }

    @Test
    @DisplayName("biner: tanpa bagian file ditolak 400")
    void binaryWithoutFileRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> service(false, true).publishFile(principal, form("2.0.0"), null));

        assertEquals("Berkas paket (file) wajib diisi.", error.getMessage());
    }

    @Test
    @DisplayName("biner: contentBase64 di medan form diabaikan; isinya hanya dari berkas")
    void formIgnoresBase64() {
        PublishPackageRequest request = PublishPackageRequest.fromForm(
                Map.of("name", " Tagihan ", "contentBase64", "QUJD"));

        assertEquals("Tagihan", request.name());
        assertEquals("1.0.0", request.version());
        assertEquals(null, request.contentBase64());
    }

    @Test
    @DisplayName("Studio: sumbernya STUDIO; versi yang bukan angka bertitik ditolak 400 dengan aturan yang sama")
    void studioSourceAndNaming() {
        service(false, true).publish(principal, request("1.0.4"));
        assertTrue(database.argumentsOf("INSERT INTO packages").contains("STUDIO"));

        ApiException error = assertThrows(ApiException.class, () -> service(false, true).publish(principal, request("v1")));
        assertEquals("PACKAGE_INVALID_VERSION", error.errorCode());
    }

    @Test
    @DisplayName("dua penerbitan versi yang sama bersamaan: yang kalah 409, bukan 500")
    void raceOnUniqueKeyIs409() {
        PackageRepository packages = new PackageRepository(database) {
            @Override
            public void insert(UUID tenantId, String name, String version, String description, String entryPoint,
                               String publishedBy, long sizeBytes, byte[] content, String sha256,
                               PackageSource source, String sourceFileName, String sourceFileSha256) {
                throw new DuplicateKeyException("uq_packages_tenant_name_version");
            }
        };
        PackageService service = new PackageService(packages, new ProcessRepository(database), new LogRepository(database),
                new AlertRepository(database), TestFolderAccess.permitAll(), (p, permission) -> true, TestProperties.defaults(), new LibraryRepository(database));

        ApiException error = assertThrows(ApiException.class, () -> service.publish(principal, request("1.0.4")));
        assertEquals("PACKAGE_VERSION_EXISTS", error.errorCode());
    }

    // ----------------------------------------------- unggah manual (V17)

    private PackageService uploadService(boolean versionExists, boolean allowed, List<Map<String, Object>> processes) {
        PackageRepository packages = new PackageRepository(database) {
            @Override
            public boolean exists(UUID tenantId, String name, String version) {
                return versionExists;
            }
        };
        ProcessRepository processRepository = new ProcessRepository(database) {
            @Override
            public List<Map<String, Object>> findPackageLinks(UUID tenantId, String name) {
                return processes;
            }
        };
        PermissionChecker checker = (p, permission) -> {
            checkedPermissions.add(permission);
            return allowed;
        };

        return new PackageService(packages, processRepository, new LogRepository(database),
                new AlertRepository(database), TestFolderAccess.permitAll(), checker, TestProperties.defaults(), new LibraryRepository(database));
    }

    /** Peramban lama mengirim jalur lengkapnya; yang disimpan nama berkasnya saja. */
    private static MockMultipartFile nupkg() {
        return new MockMultipartFile("file", "C:\\fakepath\\Record_Video.1.2.3.nupkg", "application/octet-stream",
                PackageInspectorTest.studioNupkg("1.2.3"));
    }

    @Test
    @DisplayName("unggah .nupkg: MANUAL_UPLOAD, isi ditata ulang, sidik berkas asli dicatat, proses baru dibuat")
    void uploadStored() {
        MockMultipartFile file = nupkg();
        UploadPackageResponse response = uploadService(false, true, List.of()).upload(principal, file);

        assertEquals("Record Video", response.name());
        assertEquals("1.2.3", response.version());
        assertEquals("Main.xaml", response.entryPoint());
        assertEquals("MANUAL_UPLOAD", response.source());
        assertEquals("NUPKG", response.layout());
        assertTrue(response.converted());
        assertEquals("CREATED", response.processChange());
        assertEquals(List.of("packages.create"), checkedPermissions);

        List<Object> args = database.argumentsOf("INSERT INTO packages");
        assertTrue(args.contains("MANUAL_UPLOAD"));
        assertTrue(args.contains("Record_Video.1.2.3.nupkg"));
        assertTrue(args.contains(Hashes.sha256Hex(PackageInspectorTest.studioNupkg("1.2.3"))), "sidik berkas asli");
        assertTrue(args.contains(response.sha256()), "sidik isi yang disimpan");
        assertTrue(!response.sha256().equals(Hashes.sha256Hex(PackageInspectorTest.studioNupkg("1.2.3"))));
        assertEquals(1, database.statementsContaining("INSERT INTO processes").size());
        assertTrue(database.argumentsOf("INSERT INTO alerts").stream()
                .anyMatch(a -> String.valueOf(a).contains("diunggah manual oleh fajar (Record_Video.1.2.3.nupkg)")));
    }

    @Test
    @DisplayName("unggah: versi yang sudah ada 409 PACKAGE_VERSION_EXISTS, tanpa menyimpan apa pun")
    void uploadExistingVersion() {
        ApiException error = assertThrows(ApiException.class,
                () -> uploadService(true, true, List.of()).upload(principal, nupkg()));

        assertEquals("PACKAGE_VERSION_EXISTS", error.errorCode());
        assertEquals("Paket 'Record Video' versi 1.2.3 sudah ada. Naikkan versinya, lalu terbitkan lagi.", error.getMessage());
        assertTrue(database.statementsContaining("INSERT INTO packages").isEmpty());
    }

    @Test
    @DisplayName("unggah: proses hanya dipindah ke versi yang LEBIH BARU, tidak pernah diturunkan")
    void uploadMovesOnlyOlderProcesses() {
        List<Map<String, Object>> processes = List.of(
                Map.of("id", "11111111-1111-1111-1111-111111111111", "packageVersion", "1.0.9"),
                Map.of("id", "22222222-2222-2222-2222-222222222222", "packageVersion", "1.10.0"));

        UploadPackageResponse response = uploadService(false, true, processes).upload(principal, nupkg());

        assertEquals("UPDATED", response.processChange());
        List<String> updates = database.statementsContaining("UPDATE processes SET package_name");
        assertEquals(1, updates.size());
        assertTrue(database.argumentsOf("UPDATE processes SET package_name")
                .contains(UUID.fromString("11111111-1111-1111-1111-111111111111")));

        database.statements.clear();
        database.arguments.clear();
        UploadPackageResponse older = uploadService(false, true,
                List.of(Map.of("id", "22222222-2222-2222-2222-222222222222", "packageVersion", "2.0.0")))
                .upload(principal, nupkg());
        assertEquals("UNCHANGED", older.processChange());
        assertTrue(database.statementsContaining("UPDATE processes").isEmpty());
    }

    @Test
    @DisplayName("unggah tanpa packages.create: 403 sebelum berkasnya dibaca")
    void uploadPermissionFirst() {
        MultipartFile unreadable = new MockMultipartFile("file", "A.1.0.0.zip", null, new byte[]{1}) {
            @Override
            public byte[] getBytes() {
                throw new AssertionError("isi berkas tidak boleh dibaca");
            }
        };

        ApiException error = assertThrows(ApiException.class,
                () -> uploadService(false, false, List.of()).upload(principal, unreadable));
        assertEquals(HttpStatus.FORBIDDEN, error.status());
    }

    @Test
    @DisplayName("unggah tanpa berkas: 400")
    void uploadWithoutFile() {
        ApiException error = assertThrows(ApiException.class,
                () -> uploadService(false, true, List.of()).upload(principal, null));
        assertEquals("Pilih berkas paket (.nupkg atau .zip) untuk diunggah.", error.getMessage());
    }

    // ----------------------------------------------- hapus (V17: penjaga pemakai)

    private PackageService deleteService(List<String> processes, int heldJobs) {
        PackageRepository packages = new PackageRepository(database) {
            @Override
            public List<String> findProcessesUsing(UUID tenantId, String name, String version) {
                return processes;
            }

            @Override
            public int countHeldJobsUsing(UUID tenantId, String name, String version) {
                return heldJobs;
            }
        };

        return new PackageService(packages, new ProcessRepository(database), new LogRepository(database),
                new AlertRepository(database), TestFolderAccess.permitAll(), (p, permission) -> true,
                TestProperties.defaults(), new LibraryRepository(database));
    }

    @Test
    @DisplayName("hapus versi yang disematkan proses: 409 PACKAGE_IN_USE menyebut prosesnya")
    void deleteInUseByProcess() {
        ApiException error = assertThrows(ApiException.class,
                () -> deleteService(List.of("Shared / Tagihan", "Keuangan / Tagihan"), 0).delete(principal, "Tagihan", "1.0.3"));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("PACKAGE_IN_USE", error.errorCode());
        assertEquals("Paket 'Tagihan' versi 1.0.3 masih dipakai proses: Shared / Tagihan, Keuangan / Tagihan."
                + " Pindahkan prosesnya ke versi lain dulu, lalu hapus lagi.", error.getMessage());
        assertTrue(database.statementsContaining("DELETE FROM packages").isEmpty());
    }

    @Test
    @DisplayName("hapus versi yang sedang dipegang robot: 409; tanpa pemakai: terhapus")
    void deleteHeldByJobs() {
        ApiException error = assertThrows(ApiException.class,
                () -> deleteService(List.of(), 2).delete(principal, "Tagihan", "1.0.2"));
        assertEquals("Paket 'Tagihan' versi 1.0.2 sedang dijalankan 2 pekerjaan. Tunggu sampai selesai, lalu hapus lagi.",
                error.getMessage());
        assertTrue(database.statementsContaining("DELETE FROM packages").isEmpty());

        deleteService(List.of(), 0).delete(principal, "Tagihan", "1.0.1");
        assertEquals(1, database.statementsContaining("DELETE FROM packages").size());
    }
}
