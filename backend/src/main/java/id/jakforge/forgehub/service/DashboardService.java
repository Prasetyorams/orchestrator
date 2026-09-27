package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.common.Timestamps;
import id.jakforge.forgehub.model.DashboardPeriod;
import id.jakforge.forgehub.repository.AlertRepository;
import id.jakforge.forgehub.repository.DashboardRepository;
import id.jakforge.forgehub.repository.DashboardRepository.HistoryRange;
import id.jakforge.forgehub.repository.DashboardRepository.PeriodStarts;
import id.jakforge.forgehub.repository.QueueRepository;
import id.jakforge.forgehub.repository.RobotRepository;
import id.jakforge.forgehub.repository.TriggerRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Isi halaman utama.
 *
 * <p>Seluruh isi dasbor diambil dalam SATU permintaan. Dasbor menyegarkan
 * dirinya tiap beberapa detik; sepuluh permintaan terpisah berarti sepuluh kali
 * perjalanan ke basis data tiap penyegaran, dan angka-angkanya bisa berasal
 * dari sepuluh saat yang berbeda — kartu "berjalan" tidak cocok dengan tabel di
 * bawahnya.
 *
 * <p>Batas "hari ini" dihitung dengan {@link Clock} aplikasi, yang berjalan di
 * zona TAMPILAN — bukan zona server. Di dalam container zona server adalah
 * UTC, dan orang yang membuka dasbor pukul 7 pagi WIB akan melihat angka
 * "hari ini" yang masih menghitung kemarin sore.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    static final int DAILY_HISTORY_DAYS = 14;
    static final int PANEL_ROW_LIMIT = 25;
    static final int UPCOMING_TRIGGER_LIMIT = 10;
    static final int RECENT_ALERT_LIMIT = 8;
    static final int QUEUE_SUMMARY_LIMIT = 8;

    /**
     * Irisan donat per proses. Lebih dari itu, sisanya digabung menjadi satu
     * irisan "lainnya": dua puluh irisan setipis rambut tidak terbaca sebagai
     * apa pun, dan warnanya mulai berulang.
     */
    static final int PROCESS_SLICE_LIMIT = 5;

    /** Tingkat keberhasilan periode yang belum punya pekerjaan selesai. */
    static final double FULL_SUCCESS_RATE = 100.0;

    private static final String PROCESS_NAME_COLUMN = "processName";

    private final DashboardRepository dashboardRepository;
    private final RobotRepository robotRepository;
    private final QueueRepository queueRepository;
    private final TriggerRepository triggerRepository;
    private final AlertRepository alertRepository;
    private final FolderAccessService folderAccessService;
    private final Clock clock;

    /**
     * Isi dasbor sebuah folder.
     *
     * <p>Angka pekerjaan dikirim untuk KEEMPAT periode sekaligus, di
     * {@code periods} dan {@code processBreakdown}: setiap donat di dasbor
     * memilih rentangnya sendiri, dan berganti pilihan tidak perlu menunggu
     * permintaan baru. Yang menggambarkan keadaan SAAT INI — robot, pekerjaan
     * yang sedang berjalan — tidak punya rentang.
     *
     * <p>Peringatan tetap milik seluruh penyewa: peringatan tidak tinggal di
     * folder, dan robot yang terputus perlu terlihat dari folder mana pun.
     *
     * @param folderId kosong berarti seluruh penyewa, seperti sebelum folder ada
     */
    public Map<String, Object> getDashboard(ForgeHubPrincipal principal, String folderId) {
        UUID tenantId = principal.tenantId();
        UUID folder = folderAccessService.resolveFolderFilter(principal, folderId);
        LocalDate today = LocalDate.now(clock);

        Map<DashboardPeriod, LocalDate> firstDays = new EnumMap<>(DashboardPeriod.class);
        for (DashboardPeriod period : DashboardPeriod.values()) firstDays.put(period, period.firstDay(today));

        PeriodStarts starts = new PeriodStarts(
                startOfDayUtc(firstDays.get(DashboardPeriod.TODAY)),
                startOfDayUtc(firstDays.get(DashboardPeriod.WEEK)),
                startOfDayUtc(firstDays.get(DashboardPeriod.MONTH)),
                startOfDayUtc(firstDays.get(DashboardPeriod.YEAR)));

        Map<String, Object> jobCounts = dashboardRepository.countJobs(tenantId, folder, starts);

        Map<String, Map<String, Object>> periodFigures = new LinkedHashMap<>();
        for (DashboardPeriod period : DashboardPeriod.values()) {
            periodFigures.put(period.apiName(), figuresOfPeriod(jobCounts, period.apiName(), firstDays.get(period)));
        }

        List<Map<String, Object>> processRows = dashboardRepository.countJobsByProcess(tenantId, folder, starts);
        List<String> topProcesses = selectTopProcesses(processRows);

        Map<String, List<Map<String, Object>>> processBreakdown = new LinkedHashMap<>();
        for (DashboardPeriod period : DashboardPeriod.values()) {
            processBreakdown.put(period.apiName(), buildProcessSlices(processRows, period.apiName(), topProcesses));
        }

        // "jobs" dan "successRate" tetap berbentuk seperti sebelum ada periode,
        // untuk klien yang sudah membacanya: angka ...Today selalu hari ini.
        Map<String, Object> todayFigures = periodFigures.get(DashboardPeriod.TODAY.apiName());

        Map<String, Object> jobs = new LinkedHashMap<>();
        jobs.put("running", toLong(jobCounts.get("running")));
        jobs.put("pending", toLong(jobCounts.get("pending")));
        jobs.put("stopping", toLong(jobCounts.get("stopping")));
        jobs.put("successfulToday", todayFigures.get("successful"));
        jobs.put("faultedToday", todayFigures.get("faulted"));
        jobs.put("totalToday", todayFigures.get("total"));

        Map<String, Object> library = dashboardRepository.countLibrary(tenantId, folder);

        Map<String, Object> queueCounts = queueRepository.countItemsByStatus(tenantId, folder);
        queueCounts.put("total", toLong(library.get("queues")));

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("robots", robotRepository.countByStatus(tenantId, folder));
        dashboard.put("jobs", jobs);
        dashboard.put("periods", periodFigures);
        dashboard.put("processBreakdown", processBreakdown);
        dashboard.put("queues", queueCounts);
        dashboard.put("library", library);
        dashboard.put("successRate", todayFigures.get("successRate"));
        dashboard.put("unreadAlerts", alertRepository.countUnread(tenantId));
        dashboard.put("jobsInProgress", dashboardRepository.findJobsInProgress(tenantId, folder, PANEL_ROW_LIMIT));
        dashboard.put("activeRobots", robotRepository.findForDashboard(tenantId, folder, PANEL_ROW_LIMIT));
        dashboard.put("upcomingTriggers", triggerRepository.findUpcoming(tenantId, folder, UPCOMING_TRIGGER_LIMIT));
        dashboard.put("recentAlerts", alertRepository.findRecent(tenantId, false, RECENT_ALERT_LIMIT));
        dashboard.put("queueSummary", queueRepository.findSummaries(tenantId, folder, QUEUE_SUMMARY_LIMIT));
        dashboard.put("serverTime", Timestamps.nowText());

        return dashboard;
    }

    /**
     * Proses yang mendapat irisan sendiri: paling banyak {@link #PROCESS_SLICE_LIMIT},
     * yang terbanyak sepanjang TAHUN ini — periode terlebar.
     *
     * <p>Anggotanya SAMA untuk keempat periode, dan urutannya juga. Dasbor
     * mewarnai irisan menurut urutan ini, jadi berganti dari Harian ke Bulanan
     * tidak mewarnai ulang proses yang sama dengan warna lain: warna mengikuti
     * prosesnya, bukan peringkatnya hari itu.
     */
    static List<String> selectTopProcesses(List<Map<String, Object>> processRows) {
        String widestPeriod = DashboardPeriod.YEAR.apiName();

        return processRows.stream()
                .filter(row -> toLong(row.get(widestPeriod)) > 0)
                .sorted(Comparator.<Map<String, Object>>comparingLong(row -> toLong(row.get(widestPeriod))).reversed()
                        .thenComparing(row -> String.valueOf(row.get(PROCESS_NAME_COLUMN)),
                                String.CASE_INSENSITIVE_ORDER))
                .limit(PROCESS_SLICE_LIMIT)
                .map(row -> String.valueOf(row.get(PROCESS_NAME_COLUMN)))
                .toList();
    }

    /**
     * Irisan donat per proses untuk satu periode, dalam urutan {@code topProcesses}.
     *
     * <p>Proses terpilih tetap ikut walau jumlahnya nol di periode ini —
     * keterangan donatnya tidak berganti isi setiap kali periodenya diganti.
     * Proses lain digabung menjadi satu irisan "lainnya" ({@code other: true},
     * tanpa nama), hanya kalau jumlahnya lebih dari nol.
     */
    static List<Map<String, Object>> buildProcessSlices(List<Map<String, Object>> processRows, String periodName,
                                                        List<String> topProcesses) {
        Map<String, Long> countByProcess = new LinkedHashMap<>();
        for (Map<String, Object> row : processRows) {
            countByProcess.put(String.valueOf(row.get(PROCESS_NAME_COLUMN)), toLong(row.get(periodName)));
        }

        List<Map<String, Object>> slices = new ArrayList<>();

        for (String processName : topProcesses) {
            Map<String, Object> slice = new LinkedHashMap<>();
            slice.put("name", processName);
            slice.put("count", countByProcess.getOrDefault(processName, 0L));
            slice.put("other", false);
            slices.add(slice);
        }

        long remainingCount = 0;
        int remainingProcesses = 0;

        for (Map.Entry<String, Long> entry : countByProcess.entrySet()) {
            if (topProcesses.contains(entry.getKey()) || entry.getValue() <= 0) continue;

            remainingCount += entry.getValue();
            remainingProcesses++;
        }

        if (remainingCount > 0) {
            Map<String, Object> other = new LinkedHashMap<>();
            other.put("name", null);
            other.put("count", remainingCount);
            other.put("other", true);
            other.put("processes", remainingProcesses);
            slices.add(other);
        }

        return slices;
    }

    /**
     * Batang grafik untuk satu periode: per jam hari ini, per hari minggu dan
     * bulan ini, per bulan tahun ini.
     *
     * <p>Tanpa periode, bentuk lamanya — empat belas hari terakhir — tetap
     * dilayani untuk klien yang belum mengenal periode.
     */
    public List<Map<String, Object>> getHistory(ForgeHubPrincipal principal, String periodName) {
        String zoneId = displayZone().getId();

        if (periodName == null || periodName.isBlank()) {
            return dashboardRepository.findDailyHistory(principal.tenantId(), zoneId, DAILY_HISTORY_DAYS);
        }

        DashboardPeriod period = parsePeriod(periodName);
        LocalDate firstDay = period.firstDay(LocalDate.now(clock));

        HistoryRange range = new HistoryRange(period.bucketUnit(),
                firstDay.atStartOfDay(), period.lastBucketStart(firstDay),
                startOfDayUtc(firstDay), startOfDayUtc(period.firstDayOfNextPeriod(firstDay)));

        return dashboardRepository.findPeriodHistory(principal.tenantId(), zoneId, range);
    }

    private static DashboardPeriod parsePeriod(String periodName) {
        DashboardPeriod period = DashboardPeriod.parse(periodName);

        if (period == null) {
            throw ApiException.badRequest(
                    "Periode tidak dikenal: '" + periodName.trim() + "'. Pilih today, week, month, atau year.");
        }

        return period;
    }

    private ZoneId displayZone() {
        return clock.getZone();
    }

    /** Tengah malam hari itu di zona tampilan, sebagai waktu UTC untuk dibandingkan dengan kolom. */
    private OffsetDateTime startOfDayUtc(LocalDate day) {
        return day.atStartOfDay(displayZone()).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    /**
     * Angka satu periode dari baris hitungan: hari pertamanya, jumlah yang
     * berhasil, gagal, dan dibuat, serta tingkat keberhasilannya.
     *
     * @param periodName nama periode di API, yang juga awalan kolomnya (todaySuccessful, ...)
     */
    private static Map<String, Object> figuresOfPeriod(Map<String, Object> jobCounts, String periodName,
                                                       LocalDate firstDay) {
        long successful = toLong(jobCounts.get(periodName + "Successful"));
        long faulted = toLong(jobCounts.get(periodName + "Faulted"));
        long finished = successful + faulted;

        Map<String, Object> figures = new LinkedHashMap<>();
        figures.put("start", firstDay.toString());
        figures.put("successful", successful);
        figures.put("faulted", faulted);
        figures.put("stopped", toLong(jobCounts.get(periodName + "Stopped")));
        figures.put("total", toLong(jobCounts.get(periodName + "Total")));

        // Tingkat keberhasilan dihitung dari pekerjaan yang sudah SELESAI saja.
        // Memasukkan yang masih berjalan ke penyebut membuat angkanya turun
        // tiap kali pekerjaan baru dimulai, seolah ada yang baru saja gagal.
        figures.put("successRate", finished == 0 ? FULL_SUCCESS_RATE : percentWithOneDecimal(successful, finished));

        return figures;
    }

    /**
     * Persen dengan satu angka di belakang koma: 2 dari 3 menjadi 66.7.
     *
     * <p>Dihitung sebagai per-seribu yang dibulatkan lalu dibagi sepuluh, bukan
     * persen yang dikali sepuluh: dua urutan itu bisa berbeda satu angka di
     * pembulatan titik mengambang.
     */
    private static double percentWithOneDecimal(long part, long whole) {
        return Math.round(part * 1000.0 / whole) / 10.0;
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
