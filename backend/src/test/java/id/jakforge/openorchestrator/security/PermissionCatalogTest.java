package id.jakforge.openorchestrator.security;

import id.jakforge.openorchestrator.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pola izin dan perapian daftar izin dari layar Peran. */
class PermissionCatalogTest {

    // ---------- pola ----------

    @Test
    @DisplayName("*, sumber.*, *.tindakan, dan izin persis")
    void matchesPatterns() {
        assertTrue(PermissionCatalog.matches(List.of("*"), "users.delete"));

        assertTrue(PermissionCatalog.matches(List.of("processes.*"), "processes.delete"));
        assertFalse(PermissionCatalog.matches(List.of("processes.*"), "jobs.read"));

        assertTrue(PermissionCatalog.matches(List.of("*.read"), "audit.read"));
        assertFalse(PermissionCatalog.matches(List.of("*.read"), "jobs.create"));

        assertTrue(PermissionCatalog.matches(List.of("jobs.read", "jobs.create"), "jobs.create"));
        assertFalse(PermissionCatalog.matches(List.of("jobs.read"), "jobs.update"));
        assertFalse(PermissionCatalog.matches(List.of(), "jobs.read"));
    }

    @Test
    @DisplayName("teks tersimpan: dipisah koma, dipangkas, yang kosong dibuang")
    void parsesStoredPatterns() {
        assertEquals(Set.of("a.read", "b.*", "*.read"), PermissionCatalog.parsePatterns(" a.read, b.* ,,*.read "));
        assertTrue(PermissionCatalog.parsePatterns(null).isEmpty());
        assertTrue(PermissionCatalog.parsePatterns("").isEmpty());
    }

    // ---------- layar Peran ----------

    @Test
    @DisplayName("izin dari layar Peran dirapikan: urutan katalog, sumber penuh menjadi sumber.*")
    void normalizesSelection() {
        List<String> normalized = PermissionCatalog.normalize(List.of(
                "jobs.read", "processes.delete", "processes.read", "processes.update", "processes.create",
                "jobs.read", " audit.read "));

        // audit hanya punya satu tindakan, jadi "audit.read" berarti seluruh sumbernya.
        assertEquals(List.of("processes.*", "jobs.read", "audit.*"), normalized);
    }

    @Test
    @DisplayName("sumber.* hanya untuk sumber yang SEMUA tindakannya dipilih")
    void wildcardOnlyForCompleteResources() {
        assertEquals(List.of("alerts.read"), PermissionCatalog.normalize(List.of("alerts.read")));
        assertEquals(List.of("alerts.*"), PermissionCatalog.normalize(List.of("alerts.*")));
        assertEquals(List.of("logs.*"), PermissionCatalog.normalize(List.of("logs.read", "logs.create", "logs.delete")));
        assertEquals(List.of("logs.read", "logs.delete"), PermissionCatalog.normalize(List.of("logs.delete", "logs.read")));
    }

    @Test
    @DisplayName("izin yang tidak dikenal ditolak, bukan dibuang diam-diam")
    void rejectsUnknownPermissions() {
        for (String unknown : List.of("procesess.read", "audit.update", "processes", "*", "*.read", "processes.baca")) {
            ApiException error = assertThrows(ApiException.class,
                    () -> PermissionCatalog.normalize(List.of(unknown)), unknown);
            assertEquals(HttpStatus.BAD_REQUEST, error.status(), unknown);
        }
    }

    @Test
    @DisplayName("tidak ada yang bisa memberi izin yang ia sendiri tidak punya")
    void findsFirstUncoveredPermission() {
        assertNull(PermissionCatalog.firstUncovered(List.of("*"), List.of("*")));
        assertNull(PermissionCatalog.firstUncovered(List.of("processes.*"), List.of("processes.read", "processes.delete")));
        assertNull(PermissionCatalog.firstUncovered(List.of("*.read"), List.of("jobs.read", "audit.read")));

        assertEquals("jobs.create",
                PermissionCatalog.firstUncovered(List.of("*.read"), List.of("jobs.read", "jobs.create")));

        // Administrator ("*") memuat izin pertama katalog yang tidak dimiliki.
        assertEquals("processes.create", PermissionCatalog.firstUncovered(List.of("processes.read"), List.of("*")));
    }

    @Test
    @DisplayName("katalog berisi 58 izin, dalam urutan baris matriks layar Peran")
    void catalogShape() {
        assertEquals(58, PermissionCatalog.expand(List.of("*")).size());
        assertEquals("processes", PermissionCatalog.RESOURCES.getFirst().key());
        assertEquals("settings", PermissionCatalog.RESOURCES.getLast().key());
    }
}
