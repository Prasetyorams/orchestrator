package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Hashes;
import id.jakforge.openorchestrator.dto.request.PublishPackageRequest;
import id.jakforge.openorchestrator.dto.response.PublishPackageResponse;
import id.jakforge.openorchestrator.repository.AlertRepository;
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
                new AlertRepository(database), TestFolderAccess.permitAll(), checker, TestProperties.defaults());
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
}
