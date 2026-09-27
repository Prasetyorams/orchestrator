package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.dto.request.LogBatchRequest;
import id.jakforge.openorchestrator.dto.response.LogWriteResponse;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import id.jakforge.openorchestrator.support.TestFolderAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catatan tingkat rincian (TRACE, DEBUG) tidak disimpan dan tidak ditampilkan.
 *
 * <p>Layanan dan repositorinya ASLI; yang palsu hanya basis datanya, yang
 * merekam SQL beserta argumennya. Dengan begitu yang diuji juga kalimat SQL
 * yang benar-benar dikirim, bukan hanya keputusan di layanan.
 */
class LogServiceTest {

    private final OpenOrchestratorPrincipal principal =
            new OpenOrchestratorPrincipal(UUID.randomUUID(), UUID.randomUUID(), "LAPTOP-uji", "Robot");
    private final RecordingDatabase database = new RecordingDatabase();
    private final LogService logService = new LogService(new LogRepository(database),
            new AlertRepository(database), TestFolderAccess.permitAll());

    @Test
    @DisplayName("TRACE dan DEBUG dilewati; INFO, WARN, dan ERROR disimpan")
    void verboseLevelsAreNotStored() {
        LogWriteResponse result = logService.write(principal, new LogBatchRequest(List.of(
                line("DEBUG", "Assign"),
                line("trace", "VisualBasicValue<String>"),
                line("INFO", "Workflow dimulai."),
                line("WARN", "Elemen lambat muncul."),
                line("ERROR", "Elemen tidak ditemukan."))));

        assertEquals(3, result.written());
        assertEquals(2, result.skipped());
        assertEquals(List.of("INFO", "WARN", "ERROR"), storedLevels());
    }

    @Test
    @DisplayName("ERROR tetap menjadi peringatan, DEBUG tidak")
    void onlyErrorsRaiseAlerts() {
        logService.write(principal, new LogBatchRequest(List.of(line("DEBUG", "Click"), line("ERROR", "Gagal"))));

        assertEquals(1, database.statementsContaining("INSERT INTO alerts").size());
    }

    @Test
    @DisplayName("baris tanpa tingkat dianggap INFO dan tetap disimpan")
    void lineWithoutLevelIsInfo() {
        Map<String, Object> lineWithoutLevel = new HashMap<>();
        lineWithoutLevel.put("message", "Pesan tanpa tingkat");

        LogWriteResponse result = logService.write(principal, new LogBatchRequest(List.of(lineWithoutLevel)));

        assertEquals(1, result.written());
        assertEquals(List.of("INFO"), storedLevels());
    }

    @Test
    @DisplayName("baris yang bukan objek atau tanpa pesan dilewati, bukan menggagalkan kiriman")
    void malformedLinesAreSkipped() {
        LogWriteResponse result = logService.write(principal, new LogBatchRequest(List.of(
                "bukan objek", Map.of("level", "INFO"), line("INFO", "Yang sah"))));

        assertEquals(1, result.written());
        assertEquals(List.of("INFO"), storedLevels());
    }

    @Test
    @DisplayName("kiriman tanpa larik lines ditolak 400")
    void missingLinesIsBadRequest() {
        ApiException error = assertThrows(ApiException.class,
                () -> logService.write(principal, LogBatchRequest.fromBody(Map.<String, Object>of("lines", "bukan larik"))));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertEquals("Butuh { \"lines\": [ ... ] }.", error.getMessage());
    }

    @Test
    @DisplayName("pembacaan selalu menyaring TRACE dan DEBUG yang terlanjur tersimpan")
    void readsAlwaysFilterVerboseLevels() {
        logService.search(principal, null, null, "cha", null, null, null);

        List<Object> args = database.lastArguments();

        assertTrue(database.lastStatement().contains("level NOT IN (?, ?)"), database.lastStatement());
        assertFalse(database.lastStatement().contains("level IN"), database.lastStatement());
        assertTrue(args.contains("TRACE") && args.contains("DEBUG"), args.toString());
    }

    @Test
    @DisplayName("meminta tingkat DEBUG saja dijawab kosong tanpa bertanya ke basis data")
    void onlyVerboseLevelsReturnNothing() {
        assertEquals(List.of(), logService.search(principal, List.of("debug"), null, null, null, null, null));
        assertEquals(List.of(), logService.search(principal, List.of("TRACE,debug"), null, null, null, null, null));

        assertTrue(database.statements.isEmpty(), database.statements.toString());
    }

    @Test
    @DisplayName("meminta tingkat ERROR tetap menyaring rincian dan hanya ERROR")
    void requestedLevelIsFiltered() {
        logService.search(principal, List.of("error"), null, null, null, null, null);

        assertTrue(database.lastStatement().contains("level NOT IN (?, ?)")
                && database.lastStatement().contains("level IN (?)"), database.lastStatement());
        assertEquals(List.of("ERROR"), filteredLevels());
    }

    @Test
    @DisplayName("lebih dari satu tingkat, dipisah koma maupun parameter berulang")
    void severalLevels() {
        logService.search(principal, List.of("info,error"), null, null, null, null, null);
        assertEquals(List.of("INFO", "ERROR"), filteredLevels());

        logService.search(principal, List.of("FATAL", "info"), null, null, null, null, null);
        assertEquals(List.of("INFO", "FATAL"), filteredLevels());
    }

    @Test
    @DisplayName("WARN ikut membawa WARNING, karena keduanya satu tingkat")
    void warnIncludesWarning() {
        logService.search(principal, List.of("warn"), null, null, null, null, null);
        assertEquals(List.of("WARN", "WARNING"), filteredLevels());

        logService.search(principal, List.of("warning", "WARN"), null, null, null, null, null);
        assertEquals(List.of("WARN", "WARNING"), filteredLevels());
    }

    @Test
    @DisplayName("rincian yang diminta bersama tingkat lain dibuang, tingkat lainnya tetap")
    void verboseLevelDroppedAmongOthers() {
        logService.search(principal, List.of("debug,error"), null, null, null, null, null);

        assertEquals(List.of("ERROR"), filteredLevels());
    }

    @Test
    @DisplayName("tingkat yang tidak dikenal ditolak 400, bukan diam-diam menjadi INFO")
    void unknownLevelIsRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> logService.search(principal, List.of("info,peringatan"), null, null, null, null, null));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertTrue(database.statements.isEmpty(), database.statements.toString());
    }

    @Test
    @DisplayName("jobId yang bukan UUID dijawab daftar kosong tanpa bertanya ke basis data")
    void invalidJobIdReturnsNothing() {
        assertEquals(List.of(), logService.search(principal, null, null, null, "bukan-uuid", null, null));
        assertTrue(database.statements.isEmpty(), database.statements.toString());
    }

    @Test
    @DisplayName("membersihkan catatan butuh batas hari minimal satu")
    void purgeRequiresAtLeastOneDay() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> logService.purgeOlderThan(principal, null)).status());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> logService.purgeOlderThan(principal, 0)).status());

        assertEquals(1, logService.purgeOlderThan(principal, 30).deleted());
    }

    // ---------- alat ----------

    private static Map<String, Object> line(String level, String message) {
        Map<String, Object> line = new HashMap<>();
        line.put("level", level);
        line.put("message", message);
        line.put("robotName", "LAPTOP-uji");
        return line;
    }

    /**
     * Nilai untuk "level IN (...)" pada pencarian terakhir: argumen sesudah
     * penyewa dan kedua tingkat rincian, sebelum batasnya.
     */
    private List<Object> filteredLevels() {
        List<Object> args = database.lastArguments();
        return args.subList(3, args.size() - 1);
    }

    /** Tingkat dari setiap INSERT ke tabel logs, sesuai urutan kirimannya. */
    private List<Object> storedLevels() {
        List<Object> levels = new ArrayList<>();

        for (int i = 0; i < database.statements.size(); i++) {
            if (database.statements.get(i).contains("INSERT INTO logs")) levels.add(database.arguments.get(i)[1]);
        }

        return levels;
    }
}
