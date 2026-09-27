package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.DashboardRepository;
import id.jakforge.openorchestrator.repository.QueueRepository;
import id.jakforge.openorchestrator.repository.RobotRepository;
import id.jakforge.openorchestrator.repository.TriggerRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import id.jakforge.openorchestrator.support.TestFolderAccess;
import id.jakforge.openorchestrator.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * Batas periode di dasbor: hari, minggu, bulan, dan tahun INI, di zona tampilan.
 *
 * <p>Jamnya dihentikan pada Kamis 24 September 2026 pukul 01.30 WIB — saat
 * yang sengaja dipilih: di UTC hari itu masih Rabu sore, jadi batas yang
 * dihitung di zona yang salah langsung meleset satu hari.
 */
class DashboardServiceTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final String JOB_COUNTS_SQL = "WITH period_start AS";
    private static final String LIBRARY_SQL = "WITH t AS";
    private static final String PERIOD_HISTORY_SQL = "generate_series(?::timestamp";

    private final UUID tenantId = UUID.randomUUID();
    private final OpenOrchestratorPrincipal principal =
            new OpenOrchestratorPrincipal(UUID.randomUUID(), tenantId, "OO_Admin", "Administrator");
    private final RecordingDatabase database = new RecordingDatabase();
    private final Map<String, Object> jobCounts = new HashMap<>();

    private DashboardService serviceAt(String localDateTime) {
        Instant instant = LocalDateTime.parse(localDateTime).atZone(WIB).toInstant();
        database.answerRow(JOB_COUNTS_SQL, jobCounts);

        return new DashboardService(new DashboardRepository(database),
                new RobotRepository(database, TestProperties.defaults()), new QueueRepository(database),
                new TriggerRepository(database), new AlertRepository(database), TestFolderAccess.permitAll(),
                Clock.fixed(instant, WIB));
    }

    @Test
    @DisplayName("keempat periode dihitung dalam satu kueri, dengan batas tengah malam WIB sebagai UTC")
    void allPeriodsInOneQuery() {
        Map<String, Object> dashboard = serviceAt("2026-09-24T01:30").getDashboard(principal, null);

        // Kamis 24 September, Senin 21 September, 1 September, dan 1 Januari —
        // masing-masing tengah malam WIB, yaitu pukul 17.00 UTC sehari sebelumnya.
        assertEquals(List.of(OffsetDateTime.parse("2026-09-23T17:00Z"), OffsetDateTime.parse("2026-09-20T17:00Z"),
                        OffsetDateTime.parse("2026-08-31T17:00Z"), OffsetDateTime.parse("2025-12-31T17:00Z"),
                        tenantId),
                database.argumentsOf(JOB_COUNTS_SQL));

        assertEquals("2026-09-24", periodFigures(dashboard, "today").get("start"));
        assertEquals("2026-09-21", periodFigures(dashboard, "week").get("start"));
        assertEquals("2026-09-01", periodFigures(dashboard, "month").get("start"));
        assertEquals("2026-01-01", periodFigures(dashboard, "year").get("start"));
    }

    @Test
    @DisplayName("minggu yang dibuka hari Minggu masih milik Senin sebelumnya; hari Senin milik dirinya")
    void weekStartsOnMonday() {
        assertEquals("2026-09-21",
                periodFigures(serviceAt("2026-09-27T23:59").getDashboard(principal, null), "week").get("start"));
        assertEquals("2026-09-28",
                periodFigures(serviceAt("2026-09-28T00:00").getDashboard(principal, null), "week").get("start"));
    }

    @Test
    @DisplayName("awal Januari: minggu ini boleh dimulai di tahun lalu, bulan dan tahun ini tidak")
    void weekMayCrossYearBoundary() {
        // Jumat 1 Januari 2027; minggunya dimulai Senin 28 Desember 2026.
        Map<String, Object> dashboard = serviceAt("2027-01-01T10:00").getDashboard(principal, null);

        assertEquals("2026-12-28", periodFigures(dashboard, "week").get("start"));
        assertEquals("2027-01-01", periodFigures(dashboard, "month").get("start"));
        assertEquals("2027-01-01", periodFigures(dashboard, "year").get("start"));
    }

    @Test
    @DisplayName("tingkat keberhasilan tiap periode hanya dari pekerjaan yang selesai; belum ada yang selesai berarti 100")
    void successRatePerPeriod() {
        jobCounts.putAll(Map.of(
                "weekSuccessful", 9L, "weekFaulted", 1L, "weekTotal", 13L,
                "yearSuccessful", 2L, "yearFaulted", 1L, "yearTotal", 3L));

        Map<String, Object> dashboard = serviceAt("2026-09-24T01:30").getDashboard(principal, null);

        assertEquals(100.0, periodFigures(dashboard, "today").get("successRate"));
        assertEquals(90.0, periodFigures(dashboard, "week").get("successRate"));
        assertEquals(9L, periodFigures(dashboard, "week").get("successful"));
        assertEquals(1L, periodFigures(dashboard, "week").get("faulted"));
        assertEquals(13L, periodFigures(dashboard, "week").get("total"));
        assertEquals(66.7, periodFigures(dashboard, "year").get("successRate"));
    }

    @Test
    @DisplayName("bentuk lama tetap ada: jobs.*Today dan successRate adalah angka hari ini")
    void legacyShapeIsKept() {
        jobCounts.putAll(Map.of(
                "running", 2L, "pending", 1L,
                "todaySuccessful", 3L, "todayFaulted", 1L, "todayTotal", 6L,
                "weekSuccessful", 30L, "weekFaulted", 0L));

        Map<String, Object> dashboard = serviceAt("2026-09-24T01:30").getDashboard(principal, null);
        Map<?, ?> jobs = (Map<?, ?>) dashboard.get("jobs");

        assertEquals(2L, jobs.get("running"));
        assertEquals(1L, jobs.get("pending"));
        assertEquals(3L, jobs.get("successfulToday"));
        assertEquals(1L, jobs.get("faultedToday"));
        assertEquals(6L, jobs.get("totalToday"));
        assertEquals(75.0, dashboard.get("successRate"));
    }

    @Test
    @DisplayName("dasbor sebuah folder menyaring hitungan pekerjaannya ke folder itu")
    void folderDashboardIsFiltered() {
        UUID folderId = UUID.randomUUID();

        serviceAt("2026-09-24T01:30").getDashboard(principal, folderId.toString());

        String sql = database.statementContaining(JOB_COUNTS_SQL);
        List<Object> args = database.argumentsOf(JOB_COUNTS_SQL);

        assertTrue(sql.contains("j.folder_id = ?"), sql);
        assertEquals(folderId, args.getLast());

        // Pustaka menerima foldernya lewat CTE, sekali saja.
        assertEquals(List.of(tenantId, folderId), database.argumentsOf(LIBRARY_SQL));
    }

    @Test
    @DisplayName("tanpa folder, hitungannya milik seluruh penyewa")
    void withoutFolderCountsWholeTenant() {
        serviceAt("2026-09-24T01:30").getDashboard(principal, null);

        assertFalse(database.statementContaining(JOB_COUNTS_SQL).contains("folder_id"),
                database.statementContaining(JOB_COUNTS_SQL));
    }

    @Test
    @DisplayName("yang dihentikan ikut dihitung per periode")
    void stoppedJobsPerPeriod() {
        jobCounts.putAll(Map.of("stopping", 1L, "monthStopped", 4L));

        Map<String, Object> dashboard = serviceAt("2026-09-24T01:30").getDashboard(principal, null);

        assertEquals(4L, periodFigures(dashboard, "month").get("stopped"));
        assertEquals(0L, periodFigures(dashboard, "today").get("stopped"));
        assertEquals(1L, ((Map<?, ?>) dashboard.get("jobs")).get("stopping"));
    }

    @Test
    @DisplayName("irisan donat per proses: lima terbanyak tahun ini, sisanya satu irisan lainnya")
    void processSlices() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String[] process : new String[][] {
                { "A", "9", "1" }, { "B", "7", "0" }, { "C", "5", "4" }, { "D", "4", "0" }, { "E", "3", "0" },
                { "F", "2", "2" }, { "G", "1", "1" }, { "H", "0", "0" } }) {
            rows.add(new HashMap<>(Map.of("processName", process[0],
                    "year", Long.parseLong(process[1]), "today", Long.parseLong(process[2]))));
        }

        List<String> topProcesses = DashboardService.selectTopProcesses(rows);
        assertEquals(List.of("A", "B", "C", "D", "E"), topProcesses);

        List<Map<String, Object>> slices = DashboardService.buildProcessSlices(rows, "today", topProcesses);

        // Urutannya urutan tahun, bukan urutan hari ini — C tetap di tempat
        // ketiga walau hari ini paling banyak — dan yang nol tetap ikut.
        assertEquals(List.of("A", "B", "C", "D", "E"),
                slices.subList(0, 5).stream().map(slice -> slice.get("name")).toList());
        assertEquals(List.of(1L, 0L, 4L, 0L, 0L),
                slices.subList(0, 5).stream().map(slice -> slice.get("count")).toList());

        Map<String, Object> other = slices.get(5);
        assertEquals(true, other.get("other"));
        assertEquals(3L, other.get("count"));
        assertEquals(2, other.get("processes"));
    }

    @Test
    @DisplayName("tanpa proses di luar yang terpilih, tidak ada irisan lainnya")
    void noOtherSliceWhenAllSelected() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(new HashMap<>(Map.of("processName", "P" + i, "year", 3L - i, "week", 1L)));
        }

        List<Map<String, Object>> slices =
                DashboardService.buildProcessSlices(rows, "week", DashboardService.selectTopProcesses(rows));

        assertEquals(3, slices.size());
        assertTrue(slices.stream().noneMatch(slice -> Boolean.TRUE.equals(slice.get("other"))));
    }

    @Test
    @DisplayName("grafik hari ini: 24 batang per jam")
    void todayChartHasHourlyBuckets() {
        serviceAt("2026-09-24T01:30").getHistory(principal, "today");

        assertEquals(List.of(LocalDateTime.parse("2026-09-24T00:00"), LocalDateTime.parse("2026-09-24T23:00"), "hour"),
                database.argumentsOf(PERIOD_HISTORY_SQL).subList(0, 3));
    }

    @Test
    @DisplayName("grafik bulan ini: satu batang per hari sampai akhir bulan, disaring dengan batas UTC")
    void monthChartHasDailyBuckets() {
        serviceAt("2026-09-24T01:30").getHistory(principal, "month");

        List<Object> args = database.argumentsOf(PERIOD_HISTORY_SQL);

        assertEquals(LocalDateTime.parse("2026-09-01T00:00"), args.get(0));
        assertEquals(LocalDateTime.parse("2026-09-30T00:00"), args.get(1));
        assertEquals("day", args.get(2));
        assertEquals(OffsetDateTime.parse("2026-08-31T17:00Z"), args.get(4));
        assertEquals(OffsetDateTime.parse("2026-09-30T17:00Z"), args.get(5));
        assertEquals("Asia/Jakarta", args.get(7));
    }

    @Test
    @DisplayName("grafik tahun ini: 12 batang per bulan")
    void yearChartHasMonthlyBuckets() {
        serviceAt("2026-09-24T01:30").getHistory(principal, "year");

        List<Object> args = database.argumentsOf(PERIOD_HISTORY_SQL);

        assertEquals(LocalDateTime.parse("2026-01-01T00:00"), args.get(0));
        assertEquals(LocalDateTime.parse("2026-12-01T00:00"), args.get(1));
        assertEquals("month", args.get(2));
        assertEquals(OffsetDateTime.parse("2026-12-31T17:00Z"), args.get(5));
    }

    @Test
    @DisplayName("tanpa periode, riwayat tetap empat belas hari seperti sebelumnya")
    void legacyHistoryIsFourteenDays() {
        serviceAt("2026-09-24T01:30").getHistory(principal, null);

        assertTrue(database.statements.getFirst().contains("(now() AT TIME ZONE ?)::date - (? - 1)"),
                database.statements.getFirst());
        assertEquals(14, database.lastArguments().get(1));
    }

    @Test
    @DisplayName("periode grafik yang tidak dikenal ditolak 400")
    void unknownPeriodIsRejected() {
        DashboardService dashboardService = serviceAt("2026-09-24T01:30");

        ApiException error = assertThrows(ApiException.class, () -> dashboardService.getHistory(principal, "decade"));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
    }

    // ---------- alat ----------

    private static Map<?, ?> periodFigures(Map<String, Object> dashboard, String periodName) {
        return (Map<?, ?>) ((Map<?, ?>) dashboard.get("periods")).get(periodName);
    }
}
