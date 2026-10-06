package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.dto.request.PublishPackageRequest;
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
}
