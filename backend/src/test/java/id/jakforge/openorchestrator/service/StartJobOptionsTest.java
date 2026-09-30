package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.model.RuntimeTypes;
import id.jakforge.openorchestrator.service.StartJobOptionsService.MachineOption;
import id.jakforge.openorchestrator.service.StartJobOptionsService.RobotOption;
import id.jakforge.openorchestrator.service.StartJobOptionsService.StartOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Kombinasi Start Job yang ditolak server — yang tidak akan pernah diambil
 * robot mana pun di folder itu — dan runtime mesin yang boleh disimpan.
 */
class StartJobOptionsTest {

    private final StartJobOptionsService service = new StartJobOptionsService(null, null, null);

    /** Folder dengan dua mesin: PC-01 (Production 1), VM-TEST (Testing 2); satu robot di masing-masing. */
    private static StartOptions folder() {
        return new StartOptions(RuntimeTypes.ALL,
                List.of(new MachineOption("PC-01", "Standard", true, Map.of("Production", 1)),
                        new MachineOption("VM-TEST", "Standard", false, Map.of("Testing", 2))),
                List.of(new RobotOption("ROBOT-PC", "Attended", "AVAILABLE", "PC-01", "budi", "Budi", true),
                        new RobotOption("ROBOT-VM", "Unattended", "DISCONNECTED", "VM-TEST", null, null, false)));
    }

    @Test
    @DisplayName("tipe runtime yang tersedia: yang dimiliki mesin folder, urutan katalog")
    void availableRuntimeTypes() {
        assertEquals(List.of("Production", "Testing"), folder().availableRuntimeTypes());
    }

    @Test
    @DisplayName("kombinasi yang cocok diterima")
    void validCombinations() {
        assertDoesNotThrow(() -> service.validate(folder(), "Production", null, null));
        assertDoesNotThrow(() -> service.validate(folder(), "Testing", "VM-TEST", null));
        assertDoesNotThrow(() -> service.validate(folder(), "Testing", "VM-TEST", "ROBOT-VM"));
        assertDoesNotThrow(() -> service.validate(folder(), "Production", null, "ROBOT-PC"));
    }

    @Test
    @DisplayName("runtime yang tidak dimiliki mesin mana pun di folder itu ditolak")
    void runtimeMissingInFolder() {
        assertEquals("Tidak ada mesin di folder ini yang punya runtime Development. Tambahkan runtime itu ke mesin di folder ini.",
                message(() -> service.validate(folder(), "Development", null, null)));
    }

    @Test
    @DisplayName("mesin di luar folder, atau tanpa runtime yang diminta, ditolak")
    void machineMismatch() {
        assertEquals("Mesin 'PC-99' tidak tersedia di folder ini.",
                message(() -> service.validate(folder(), "Production", "PC-99", null)));
        assertEquals("Mesin 'PC-01' tidak punya runtime Testing.",
                message(() -> service.validate(folder(), "Testing", "PC-01", null)));
    }

    @Test
    @DisplayName("robot di luar folder, di mesin lain, atau yang mesinnya tanpa runtime itu ditolak")
    void robotMismatch() {
        assertEquals("Robot 'ASING' tidak bisa menjalankan pekerjaan di folder ini.",
                message(() -> service.validate(folder(), "Production", null, "ASING")));
        assertEquals("Robot 'ROBOT-PC' tidak berada di mesin 'VM-TEST'.",
                message(() -> service.validate(folder(), "Testing", "VM-TEST", "ROBOT-PC")));
        assertEquals("Mesin robot 'ROBOT-VM' tidak punya runtime Production.",
                message(() -> service.validate(folder(), "Production", null, "ROBOT-VM")));
    }

    // -----------------------------------------------------------------
    // Runtime mesin (MachineService)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("runtime mesin dibakukan ke urutan katalog; nol dibuang")
    void runtimesAreNormalized() {
        Map<String, Integer> requested = new LinkedHashMap<>();
        requested.put("testing", 1);
        requested.put("Development", 0);
        requested.put("PRODUCTION", 2);

        assertEquals(List.of(Map.entry("Production", 2), Map.entry("Testing", 1)),
                List.copyOf(MachineService.normalizeRuntimes(requested).entrySet()));
    }

    @Test
    @DisplayName("tipe runtime tak dikenal atau jumlah di luar 0–50 ditolak")
    void invalidRuntimes() {
        assertEquals("Tipe runtime tidak dikenal: 'Produksi'. Pilih Production, Testing, atau Development.",
                message(() -> MachineService.normalizeRuntimes(Map.of("Produksi", 1))));
        assertEquals("Jumlah runtime Testing harus 0 sampai 50.",
                message(() -> MachineService.normalizeRuntimes(Map.of("Testing", 51))));
        assertEquals("Jumlah runtime Production harus 0 sampai 50.",
                message(() -> MachineService.normalizeRuntimes(Map.of("Production", -1))));
    }

    private static String message(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(ApiException.class, action).getMessage();
    }
}
