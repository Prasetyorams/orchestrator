package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.dto.request.LogBatchRequest;
import id.jakforge.openorchestrator.dto.request.LogSearchRequest;
import id.jakforge.openorchestrator.dto.response.LogWriteResponse;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import id.jakforge.openorchestrator.support.TestFolderAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** 30 Sep 2026 12.00 WIB — "hari ini" dan "kemarin" dihitung di zona ini. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-30T05:00:00Z"), ZoneId.of("Asia/Jakarta"));

    private final LogService logService = new LogService(new LogRepository(database),
            new AlertRepository(database), TestFolderAccess.permitAll(), clock);

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
    @DisplayName("hostIdentity dari robot dipangkas dan disimpan; yang melebihi kolomnya diabaikan, bukan menggagalkan kiriman")
    void hostIdentityFromRobot() {
        Map<String, Object> withHost = new HashMap<>(line("INFO", "Dengan akun"));
        withHost.put("hostIdentity", "  VM-01\\robot  ");
        Map<String, Object> tooLong = new HashMap<>(line("INFO", "Akun kepanjangan"));
        tooLong.put("hostIdentity", "x".repeat(201));

        LogWriteResponse result = logService.write(principal, new LogBatchRequest(List.of(withHost, tooLong)));

        assertEquals(2, result.written());
        List<Object[]> inserts = logInserts();
        assertEquals("VM-01\\robot", inserts.get(0)[8]);
        assertNull(inserts.get(1)[8]);
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

    // ---------- saringan halaman Catatan ----------

    @Test
    @DisplayName("rentang waktu siap pakai dihitung mundur dari sekarang, tanpa batas atas")
    void recentRanges() {
        assertEquals(new LogService.TimeRange(Instant.parse("2026-09-30T04:45:00Z"), null),
                LogService.timeRange("15m", null, null, clock));
        assertEquals(new LogService.TimeRange(Instant.parse("2026-09-29T05:00:00Z"), null),
                LogService.timeRange("24h", null, null, clock));
        assertEquals(new LogService.TimeRange(Instant.parse("2026-09-23T05:00:00Z"), null),
                LogService.timeRange("7D", null, null, clock));
        assertEquals(new LogService.TimeRange(null, null), LogService.timeRange("all", null, null, clock));
        assertEquals(new LogService.TimeRange(null, null), LogService.timeRange(null, null, null, clock));
    }

    @Test
    @DisplayName("hari ini dan kemarin mengikuti zona tampilan, bukan UTC")
    void todayAndYesterdayUseDisplayZone() {
        // Tengah malam WIB = 17.00 UTC hari sebelumnya.
        assertEquals(new LogService.TimeRange(Instant.parse("2026-09-29T17:00:00Z"), null),
                LogService.timeRange("today", null, null, clock));
        assertEquals(new LogService.TimeRange(Instant.parse("2026-09-28T17:00:00Z"), Instant.parse("2026-09-29T17:00:00Z")),
                LogService.timeRange("yesterday", null, null, clock));
    }

    @Test
    @DisplayName("rentang khusus: dengan zona, tanpa zona (zona tampilan), atau hanya satu sisi")
    void customRange() {
        assertEquals(new LogService.TimeRange(Instant.parse("2026-09-30T01:00:00Z"), Instant.parse("2026-09-30T03:00:00Z")),
                LogService.timeRange("custom", "2026-09-30T08:00:00+07:00", "2026-09-30T03:00:00Z", clock));
        assertEquals(new LogService.TimeRange(Instant.parse("2026-09-30T01:00:00Z"), null),
                LogService.timeRange("custom", "2026-09-30T08:00", null, clock));
        assertEquals(new LogService.TimeRange(null, Instant.parse("2026-09-30T01:00:00Z")),
                LogService.timeRange(null, null, "2026-09-30T08:00", clock));
    }

    @Test
    @DisplayName("rentang yang tidak dikenal, waktu yang tak terbaca, dan awal sesudah akhir ditolak 400")
    void invalidRanges() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> LogService.timeRange("2h", null, null, clock)).status());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> LogService.timeRange("custom", "kemarin", null, clock)).status());
        assertEquals("Waktu awal harus sebelum waktu akhir.", assertThrows(ApiException.class,
                () -> LogService.timeRange("custom", "2026-09-30T09:00", "2026-09-30T08:00", clock)).getMessage());
    }

    @Test
    @DisplayName("saringan mesin, host identity, waktu, dan teks digabung dengan AND; % dan _ dicari apa adanya")
    void newFiltersAreCombined() {
        logService.search(principal, new LogSearchRequest(List.of("error"), null, "Tagihan", null, null, null,
                "VM-01", "VM-01\\robot", "1h", null, null, " 100%_selesai ", null));

        String sql = database.lastStatement();
        List<Object> args = database.lastArguments();

        for (String condition : List.of("level IN (?)", "process_name = ?", "machine_name = ?", "host_identity = ?",
                "logged_at >= ?", "message ILIKE ? ESCAPE")) {
            assertTrue(sql.contains(condition), condition + " — " + sql);
        }

        assertFalse(sql.contains("logged_at < ?"), "rentang siap pakai tidak punya batas atas: " + sql);
        assertTrue(args.contains("VM-01") && args.contains("VM-01\\robot") && args.contains("Tagihan"), args.toString());
        assertEquals("%100\\%\\_selesai%", args.get(args.size() - 2), "teks dicari apa adanya, tanpa spasi tepi");
    }

    @Test
    @DisplayName("saringan pemicu (V14): ejaan bebas dibakukan; pemicu tak dikenal ditolak 400 tanpa kueri")
    void triggerFilter() {
        logService.search(principal, new LogSearchRequest(null, null, null, null, null, null, null, null, null, null,
                null, null, " Local_Schedule "));

        assertTrue(database.lastStatement().contains("run_trigger = ?"), database.lastStatement());
        assertTrue(database.lastArguments().contains("local-schedule"), database.lastArguments().toString());

        int before = database.statements.size();
        ApiException error = assertThrows(ApiException.class, () -> logService.search(principal,
                new LogSearchRequest(null, null, null, null, null, null, null, null, null, null, null, null, "cron")));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertEquals("Pemicu tidak dikenal: 'cron'. Pilih job, manual, atau local-schedule.", error.getMessage());
        assertEquals(before, database.statements.size());
    }

    @Test
    @DisplayName("baris dari robot membawa trigger (V14): dibakukan; yang tidak dikenal diabaikan, bukan menggagalkan")
    void writeKeepsTrigger() {
        logService.write(principal, new LogBatchRequest(List.of(
                Map.of("message", "Mulai menjalankan Tagihan", "trigger", "LOCAL-SCHEDULE"),
                Map.of("message", "Play", "trigger", "manual"),
                Map.of("message", "Aneh", "trigger", "cron"))));

        List<String> inserts = database.statementsContaining("INSERT INTO logs");
        assertEquals(3, inserts.size(), database.statements.toString());

        List<Object> triggers = database.arguments.stream()
                .filter(args -> args.length == 10)
                .map(args -> args[9])
                .toList();
        assertEquals(java.util.Arrays.asList("local-schedule", "manual", null), triggers);
    }

    @Test
    @DisplayName("pilihan saringan hanya dibatasi folder, pekerjaan, dan waktu — bukan tingkat atau mesin")
    void filterOptionsIgnoreValueFilters() {
        logService.filterOptions(principal, new LogSearchRequest(List.of("error"), null, "Tagihan", null, null, null,
                "VM-01", null, "today", null, null, "gagal", "manual"));

        String sql = database.lastStatement();

        assertTrue(sql.contains("GROUPING SETS") && sql.contains("logged_at >= ?"), sql);
        assertFalse(sql.contains("level IN") || sql.contains("machine_name = ?") || sql.contains("process_name = ?")
                || sql.contains("ILIKE") || sql.contains("run_trigger"), sql);
    }

    @Test
    @DisplayName("pilihan saringan untuk tingkat rincian saja: tiga daftar kosong, urutan kunci tetap, tanpa kueri")
    void filterOptionsForVerboseOnlyAreEmpty() {
        var options = logService.filterOptions(principal, LogSearchRequest.of(List.of("debug"), null, null, null, null,
                null));

        assertEquals(List.of("machines", "processes", "hostIdentities"), List.copyOf(options.keySet()));
        assertTrue(options.values().stream().allMatch(List::isEmpty), options.toString());
        assertTrue(database.statements.isEmpty(), database.statements.toString());
    }

    @Test
    @DisplayName("teks pencarian lebih dari 200 karakter ditolak sebelum bertanya ke basis data")
    void searchTextTooLong() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class, () -> logService.search(principal,
                new LogSearchRequest(null, null, null, null, null, null, null, null, null, null, null,
                        "x".repeat(201), null))).status());
        assertTrue(database.statements.isEmpty(), database.statements.toString());
    }

    @Test
    @DisplayName("ekspor CSV: BOM, kepala kolom dengan zona tampilan, nama berkas berwaktu")
    void exportHasHeaderAndName() {
        var file = logService.export(principal, LogSearchRequest.of(null, null, null, null, null, null));
        String csv = new String(file.content(), java.nio.charset.StandardCharsets.UTF_8);

        assertEquals("catatan-20260930-120000.csv", file.fileName());
        assertEquals("text/csv; charset=UTF-8", file.contentType());
        assertTrue(csv.startsWith("﻿Waktu (Asia/Jakarta),Tingkat,Robot,Mesin,Host Identity,Proses,Pekerjaan,Pemicu,Pesan\r\n"),
                csv);
        assertTrue(database.lastStatement().contains("LIMIT ?")
                && database.lastArguments().getLast().equals(LogService.MAX_EXPORT_ROWS), database.lastArguments().toString());
    }

    @Test
    @DisplayName("sel CSV: tanda kutip, koma, baris baru, dan rumus spreadsheet dijinakkan")
    void csvCells() {
        assertEquals("biasa", LogService.csvCell("biasa"));
        assertEquals("\"a, b\"", LogService.csvCell("a, b"));
        assertEquals("\"kata \"\"kutip\"\"\"", LogService.csvCell("kata \"kutip\""));
        assertEquals("\"baris\nbaru\"", LogService.csvCell("baris\nbaru"));
        assertEquals("'=HYPERLINK(1)", LogService.csvCell("=HYPERLINK(1)"));
        assertEquals("'@SUM(A1)", LogService.csvCell("@SUM(A1)"));
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
        return logInserts().stream().map(args -> args[1]).toList();
    }

    /** Argumen setiap INSERT ke tabel logs, sesuai urutan kirimannya. */
    private List<Object[]> logInserts() {
        List<Object[]> inserts = new ArrayList<>();

        for (int i = 0; i < database.statements.size(); i++) {
            if (database.statements.get(i).contains("INSERT INTO logs")) inserts.add(database.arguments.get(i));
        }

        return inserts;
    }
}
