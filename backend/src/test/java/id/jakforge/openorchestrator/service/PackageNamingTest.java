package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Satu aturan nama dan versi untuk Terbitkan dari Studio dan unggah manual. */
class PackageNamingTest {

    @Test
    @DisplayName("nama dan versi yang dikirim Studio tetap diterima")
    void studioNamesAccepted() {
        for (String name : new String[]{"Tagihan", "Record Video", "REFramework Antrean", "cha", "Proyek_1.v2"}) {
            assertDoesNotThrow(() -> PackageNaming.validate(name, "1.0.3"));
        }
        for (String version : new String[]{"1.0", "1.0.0", "10.20.30.40", "1.0.3-beta", "1.0.3-beta.2", "2.0.0+build.7"}) {
            assertDoesNotThrow(() -> PackageNaming.validate("Tagihan", version));
        }
    }

    @Test
    @DisplayName("nama dengan / \\ atau karakter kendali, dan versi yang bukan angka bertitik: 400")
    void invalidRejected() {
        for (String name : new String[]{"a/b", "a\\b", "a\u0000b", "..", " "}) {
            ApiException e = assertThrows(ApiException.class, () -> PackageNaming.validate(name, "1.0.0"));
            assertEquals(PackageNaming.INVALID_NAME, e.errorCode());
        }
        assertEquals(PackageNaming.INVALID_NAME,
                assertThrows(ApiException.class, () -> PackageNaming.validate("x".repeat(201), "1.0.0")).errorCode());

        for (String version : new String[]{"", "1", "v1.0.0", "1.0.0.0.0", "1..0", "1.0.0-", "terbaru", null}) {
            ApiException e = assertThrows(ApiException.class, () -> PackageNaming.validate("Tagihan", version));
            assertEquals(PackageNaming.INVALID_VERSION, e.errorCode(), String.valueOf(version));
            assertTrue(e.getMessage().contains("mis. 1.0.3"), e.getMessage());
        }
    }

    @Test
    @DisplayName("urutan versi: angka sebagai angka, pra-rilis di bawah rilisnya, build diabaikan")
    void compare() {
        assertTrue(PackageNaming.compare("1.0.10", "1.0.9") > 0);
        assertTrue(PackageNaming.compare("1.0", "1.0.0") == 0);
        assertTrue(PackageNaming.compare("1.0.0-beta", "1.0.0") < 0);
        assertTrue(PackageNaming.compare("1.0.0-beta.10", "1.0.0-beta.9") > 0);
        assertTrue(PackageNaming.compare("1.0.0-alpha", "1.0.0-alpha.1") < 0);
        assertTrue(PackageNaming.compare("1.0.0-1", "1.0.0-alpha") < 0);
        assertTrue(PackageNaming.compare("2.0.0+abc", "2.0.0+xyz") == 0);
        assertTrue(PackageNaming.compare("1.0.0-beta.99999999999999999999", "1.0.0-beta.2") > 0);
        assertTrue(PackageNaming.compare("1.2.0", "1.10.0") < 0);
    }
}
