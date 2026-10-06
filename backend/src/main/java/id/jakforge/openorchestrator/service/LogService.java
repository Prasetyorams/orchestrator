package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.PageLimits;
import id.jakforge.openorchestrator.common.RequestBodies;
import id.jakforge.openorchestrator.common.Strings;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.dto.request.LogBatchRequest;
import id.jakforge.openorchestrator.dto.request.LogSearchRequest;
import id.jakforge.openorchestrator.dto.response.DeletedCountResponse;
import id.jakforge.openorchestrator.dto.response.LogWriteResponse;
import id.jakforge.openorchestrator.model.FileContent;
import id.jakforge.openorchestrator.model.LogLevel;
import id.jakforge.openorchestrator.model.RunTriggers;
import id.jakforge.openorchestrator.model.Severity;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Aturan tentang catatan jalannya automasi. */
@Service
@RequiredArgsConstructor
public class LogService {

    static final int DEFAULT_PAGE_SIZE = 200;

    /**
     * Batas atas satu permintaan.
     *
     * <p>2000 baris kira-kira satu megabita JSON. Lebih dari itu bukan lagi
     * "melihat catatan", melainkan mengunduh basis data lewat pintu yang tidak
     * dirancang untuk itu.
     */
    static final int MAX_PAGE_SIZE = 2000;

    /** Batas baris per kiriman dari robot. */
    static final int MAX_LINES_PER_BATCH = 1000;

    /** Batas terkecil umur catatan yang boleh dibuang, dalam hari. */
    static final int MIN_PURGE_AGE_DAYS = 1;

    /**
     * Batas baris satu ekspor CSV. Jauh di atas yang terlihat di layar (500),
     * tapi tetap terbatas: ekspor tanpa batas dari tabel yang paling cepat
     * membesar adalah cara termudah membuat server kehabisan memori.
     */
    static final int MAX_EXPORT_ROWS = 50_000;

    /** Nilai per jenis di daftar pilihan saringan Mesin, Proses, dan Host Identity. */
    static final int MAX_FILTER_OPTIONS = 500;

    /** Batas panjang teks yang dicari. */
    static final int MAX_SEARCH_LENGTH = 200;

    /** Panjang kolom logs.host_identity (V10). */
    static final int MAX_HOST_IDENTITY_LENGTH = 200;

    /** Rentang waktu siap pakai: kunci di alamat halaman → lama ke belakang dari sekarang. */
    static final Map<String, Duration> RECENT_RANGES = Map.of(
            "15m", Duration.ofMinutes(15),
            "30m", Duration.ofMinutes(30),
            "1h", Duration.ofHours(1),
            "6h", Duration.ofHours(6),
            "12h", Duration.ofHours(12),
            "24h", Duration.ofHours(24),
            "7d", Duration.ofDays(7));

    private static final String ALERT_SOURCE = "logs";
    private static final String UNKNOWN_ROBOT = "robot";
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter EXPORT_FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final LogRepository logRepository;
    private final AlertRepository alertRepository;
    private final FolderAccessService folderAccessService;
    private final Clock clock;

    /** Bentuk lama: tanpa mesin, host identity, waktu, dan teks. */
    public List<Map<String, Object>> search(OpenOrchestratorPrincipal principal, List<String> levels, String robotName,
                                            String processName, String jobId, String folderId, Integer limit) {
        return search(principal, LogSearchRequest.of(levels, robotName, processName, jobId, folderId, limit));
    }

    /**
     * Catatan terbaru yang cocok dengan SEMUA saringan (AND).
     *
     * <p>Tingkat yang ingin dilihat boleh lebih dari satu, dari
     * {@code ?level=INFO,WARN} maupun {@code ?level=INFO&level=WARN}; kosong
     * berarti semua tingkat.
     */
    public List<Map<String, Object>> search(OpenOrchestratorPrincipal principal, LogSearchRequest request) {
        return toFilter(principal, request)
                .map(filter -> logRepository.search(principal.tenantId(), filter,
                        PageLimits.clamp(request.limit(), DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE)))
                .orElse(List.of());
    }

    /**
     * Pilihan saringan Mesin, Proses, dan Host Identity: nilai yang ada di
     * catatan folder dan rentang waktu itu. Saringan lain tidak menyempitkan
     * pilihannya — memilih Mesin tidak boleh membuat pilihan Mesin lain hilang.
     */
    public Map<String, List<String>> filterOptions(OpenOrchestratorPrincipal principal, LogSearchRequest request) {
        return toFilter(principal, request)
                .map(filter -> logRepository.findFilterOptions(principal.tenantId(), filter.scope(), MAX_FILTER_OPTIONS))
                .orElseGet(LogService::noFilterOptions);
    }

    /** Tiga daftar kosong, dengan urutan kunci yang sama seperti jawaban biasa (bukan Map.of yang acak). */
    private static Map<String, List<String>> noFilterOptions() {
        Map<String, List<String>> options = new LinkedHashMap<>();
        options.put("machines", List.of());
        options.put("processes", List.of());
        options.put("hostIdentities", List.of());
        return options;
    }

    /**
     * Ekspor CSV dengan saringan yang SAMA dengan yang tampil di layar — yang
     * diunduh adalah yang sedang dilihat, bukan seluruh catatan.
     *
     * <p>UTF-8 dengan BOM supaya Excel membaca huruf non-ASCII dengan benar.
     * Waktu ditulis di zona tampilan. Sel yang diawali =, +, -, @ diberi
     * tanda kutip tunggal: pesan catatan datang dari workflow, dan spreadsheet
     * menjalankan sel seperti itu sebagai rumus.
     */
    public FileContent export(OpenOrchestratorPrincipal principal, LogSearchRequest request) {
        List<Map<String, Object>> rows = toFilter(principal, request)
                .map(filter -> logRepository.search(principal.tenantId(), filter, MAX_EXPORT_ROWS))
                .orElse(List.of());

        ZoneId zone = clock.getZone();
        StringBuilder csv = new StringBuilder("﻿");

        csvRow(csv, "Waktu (" + zone.getId() + ")", "Tingkat", "Robot", "Mesin", "Host Identity", "Proses",
                "Pekerjaan", "Pemicu", "Pesan");

        for (Map<String, Object> row : rows) {
            Object loggedAt = row.get("loggedAt");

            csvRow(csv,
                    loggedAt == null ? "" : CSV_TIME.format(Instant.parse(loggedAt.toString()).atZone(zone)),
                    text(row.get("level")), text(row.get("robotName")), text(row.get("machineName")),
                    text(row.get("hostIdentity")), text(row.get("processName")), text(row.get("jobId")),
                    triggerLabel(row.get("trigger")), text(row.get("message")));
        }

        String fileName = "catatan-" + EXPORT_FILE_TIME.format(LocalDateTime.now(clock)) + ".csv";

        return new FileContent(fileName, "text/csv; charset=UTF-8", csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Permintaan menjadi saringan repositori, sesudah diperiksa.
     *
     * @return kosong kalau saringannya tidak mungkin cocok dengan apa pun —
     *         jobId yang bukan UUID, atau hanya tingkat rincian — tanpa
     *         bertanya ke basis data
     */
    private Optional<LogRepository.Filter> toFilter(OpenOrchestratorPrincipal principal, LogSearchRequest request) {
        UUID folder = folderAccessService.resolveFolderFilter(principal, request.folderId());
        UUID job = null;

        if (request.jobId() != null && !request.jobId().isBlank()) {
            job = Uuids.parseOrNull(request.jobId());

            // Id yang bukan UUID tidak akan pernah cocok dengan apa pun. Yang
            // dikembalikan daftar kosong, bukan galat penguraian dari basis data.
            if (job == null) return Optional.empty();
        }

        Set<LogLevel> requestedLevels = parseRequestedLevels(request.levels());

        // Tingkat rincian tidak pernah ditampilkan (lihat LogLevel.isVerbose).
        // Yang HANYA meminta rincian memang tidak mendapat apa-apa; yang
        // memintanya bersama tingkat lain mendapat tingkat lain itu saja.
        if (!requestedLevels.isEmpty() && requestedLevels.stream().allMatch(LogLevel::isVerbose)) {
            return Optional.empty();
        }

        Set<String> storedSpellings = new LinkedHashSet<>();

        for (LogLevel level : requestedLevels) {
            if (!level.isVerbose()) storedSpellings.addAll(level.storedSpellings());
        }

        TimeRange range = timeRange(request.time(), request.from(), request.to(), clock);

        String text = Strings.trimToNull(request.q());

        if (text != null && text.length() > MAX_SEARCH_LENGTH) {
            throw ApiException.badRequest("Teks pencarian paling panjang " + MAX_SEARCH_LENGTH + " karakter.");
        }

        // Pemicu jalan (V14): job, manual, atau jadwal lokal. Yang salah eja
        // ditolak — dasbor hanya mengirim yang dikenal.
        String trigger = null;

        if (request.trigger() != null && !request.trigger().isBlank()) {
            trigger = RunTriggers.normalize(request.trigger());

            if (trigger == null) {
                throw ApiException.badRequest("Pemicu tidak dikenal: '" + request.trigger().trim()
                        + "'. Pilih job, manual, atau local-schedule.");
            }
        }

        return Optional.of(new LogRepository.Filter(storedSpellings, request.robot(), request.process(), job, folder,
                Strings.trimToNull(request.machine()), Strings.trimToNull(request.host()), range.from(), range.to(),
                text, trigger));
    }

    /** Pemicu untuk ekspor CSV, dalam bahasa yang dibaca orang. */
    static String triggerLabel(Object trigger) {
        if (trigger == null) return "";

        return switch (trigger.toString()) {
            case RunTriggers.JOB -> "Job";
            case RunTriggers.MANUAL -> "Manual";
            case RunTriggers.LOCAL_SCHEDULE -> "Jadwal lokal";
            default -> trigger.toString();
        };
    }

    /** Batas waktu saringan; null = tidak dibatasi di sisi itu. {@code to} tidak termasuk. */
    record TimeRange(Instant from, Instant to) {
    }

    /**
     * Rentang waktu dari kunci siap pakai, atau dari {@code from}/{@code to}.
     *
     * <p>"today" dan "yesterday" dihitung di zona TAMPILAN (zona {@link Clock}
     * aplikasi) — "hari ini" yang sama dengan kartu di Beranda — bukan zona
     * peramban atau UTC.
     */
    static TimeRange timeRange(String time, String from, String to, Clock clock) {
        String key = Strings.trimToNull(time);
        Instant now = clock.instant();

        if (key == null || key.equalsIgnoreCase("all")) {
            // Tanpa kunci tapi dengan batas: dianggap rentang khusus.
            return from == null && to == null ? new TimeRange(null, null) : customRange(from, to, clock);
        }

        key = key.toLowerCase(Locale.ROOT);

        Duration recent = RECENT_RANGES.get(key);
        if (recent != null) return new TimeRange(now.minus(recent), null);

        LocalDate today = LocalDate.now(clock);

        return switch (key) {
            case "today" -> new TimeRange(today.atStartOfDay(clock.getZone()).toInstant(), null);
            case "yesterday" -> new TimeRange(today.minusDays(1).atStartOfDay(clock.getZone()).toInstant(),
                    today.atStartOfDay(clock.getZone()).toInstant());
            case "custom" -> customRange(from, to, clock);
            default -> throw ApiException.badRequest("Rentang waktu tidak dikenal: '" + time.trim()
                    + "'. Pilih 15m, 30m, 1h, 6h, 12h, 24h, today, yesterday, 7d, atau custom.");
        };
    }

    private static TimeRange customRange(String from, String to, Clock clock) {
        Instant start = parseInstant(from, clock);
        Instant end = parseInstant(to, clock);

        if (start != null && end != null && !start.isBefore(end)) {
            throw ApiException.badRequest("Waktu awal harus sebelum waktu akhir.");
        }

        return new TimeRange(start, end);
    }

    /** ISO-8601 dengan zona, atau tanpa zona — yang terakhir dibaca di zona tampilan. */
    private static Instant parseInstant(String text, Clock clock) {
        String value = Strings.trimToNull(text);
        if (value == null) return null;

        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException withoutZone) {
            try {
                return LocalDateTime.parse(value).atZone(clock.getZone()).toInstant();
            } catch (DateTimeParseException e) {
                throw ApiException.badRequest("Waktu tidak bisa dibaca: '" + value
                        + "'. Pakai ISO-8601, mis. 2026-09-30T08:00:00+07:00.");
            }
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /** Satu baris CSV (RFC 4180), dengan penjagaan rumus spreadsheet. */
    private static void csvRow(StringBuilder csv, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) csv.append(',');
            csv.append(csvCell(cells[i]));
        }

        csv.append("\r\n");
    }

    static String csvCell(String value) {
        String cell = value;

        if (!cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) cell = "'" + cell;

        boolean quoted = cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r");
        return quoted ? "\"" + cell.replace("\"", "\"\"") + "\"" : cell;
    }

    /**
     * Tingkat yang diminta, sebagai himpunan.
     *
     * <p>Koma dipecah di sini juga, bukan hanya diserahkan ke Spring: bentuk
     * {@code ?level=INFO,WARN} harus tetap berarti dua tingkat walaupun
     * parameternya kelak dibaca sebagai satu untai.
     */
    static Set<LogLevel> parseRequestedLevels(List<String> rawLevels) {
        Set<LogLevel> levels = EnumSet.noneOf(LogLevel.class);
        if (rawLevels == null) return levels;

        for (String rawLevel : rawLevels) {
            if (rawLevel == null) continue;

            for (String text : rawLevel.split(",")) {
                if (text.isBlank()) continue;

                LogLevel level = LogLevel.parseStrict(text);

                if (level == null) {
                    throw ApiException.badRequest("Tingkat catatan tidak dikenal: '" + text.trim() + "'.");
                }

                levels.add(level);
            }
        }

        return levels;
    }

    /**
     * Tulis sekelompok baris dari robot.
     *
     * <p>Baris yang cacat DILEWATI, bukan menggagalkan seluruh kiriman: satu
     * salah ketik pada satu baris tidak boleh membuang sembilan ratus baris
     * lain, dan yang hilang kemudian justru catatan di sekitar kegagalan yang
     * sedang dicari orang.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public LogWriteResponse write(OpenOrchestratorPrincipal principal, LogBatchRequest request) {
        if (request.lines() == null) {
            throw ApiException.badRequest("Butuh { \"lines\": [ ... ] }.");
        }

        if (request.lines().size() > MAX_LINES_PER_BATCH) {
            throw ApiException.badRequest(
                    "Terlalu banyak baris dalam satu kiriman. Batasnya " + MAX_LINES_PER_BATCH + ".");
        }

        UUID tenantId = principal.tenantId();
        int written = 0;
        int skipped = 0;

        for (Object item : request.lines()) {
            if (!(item instanceof Map)) continue;

            Map<String, Object> line = (Map<String, Object>) item;

            String message = RequestBodies.text(line, "message");
            if (message == null || message.isBlank()) continue;

            LogLevel level = LogLevel.parseOrInfo(RequestBodies.text(line, "level"));

            // TRACE dan DEBUG tidak disimpan — lihat LogLevel.isVerbose. Ditolak
            // di sini, bukan hanya disembunyikan saat dibaca: jejak per-activity
            // yang tidak pernah ditampilkan hanya memenuhi tabel dan
            // memperlambat setiap pencarian.
            if (level.isVerbose()) {
                skipped++;
                continue;
            }

            // hostIdentity (akun Windows robot) boleh dikirim robot; yang tidak
            // dikirim diisi basis data dari pekerjaan atau robotnya, sama
            // seperti mesin (V10). Yang melebihi kolomnya diabaikan, bukan
            // menggagalkan seluruh kiriman — lalu diisi dengan cara yang sama.
            String hostIdentity = Strings.trimToNull(RequestBodies.text(line, "hostIdentity"));
            if (hostIdentity != null && hostIdentity.length() > MAX_HOST_IDENTITY_LENGTH) hostIdentity = null;

            // trigger (V14): "manual" atau "local-schedule" untuk jalan lokal Open
            // Assistant, supaya halaman Catatan bisa membedakannya dari job. Yang
            // tidak dikenal diabaikan; baris yang menyebut pekerjaan diisi 'job'
            // oleh basis data.
            String trigger = RunTriggers.normalize(RequestBodies.text(line, "trigger"));

            logRepository.insert(tenantId, level, message,
                    RequestBodies.text(line, "robotName"), RequestBodies.text(line, "machineName"),
                    RequestBodies.text(line, "processName"), Uuids.parseOrNull(RequestBodies.text(line, "jobId")),
                    RequestBodies.text(line, "loggedAt"), hostIdentity, trigger);

            written++;

            // Kesalahan dari robot juga menjadi peringatan. Log dibaca kalau ada
            // yang sengaja mencarinya; peringatan muncul sendiri.
            if (level.raisesAlert()) {
                alertRepository.insert(tenantId, Severity.Error,
                        "Kesalahan pada " + RequestBodies.text(line, "robotName", UNKNOWN_ROBOT), message,
                        ALERT_SOURCE);
            }
        }

        return new LogWriteResponse(true, written, skipped);
    }

    /**
     * Buang catatan lama.
     *
     * <p>Batas hari WAJIB dan minimal satu: "hapus semua log" adalah perintah
     * yang tidak bisa dibatalkan, dan bentuk yang paling mudah dijalankan tanpa
     * sengaja.
     */
    public DeletedCountResponse purgeOlderThan(OpenOrchestratorPrincipal principal, Integer days) {
        if (days == null || days < MIN_PURGE_AGE_DAYS) {
            throw ApiException.badRequest("Parameter 'olderThanDays' wajib diisi dan minimal 1.");
        }

        return new DeletedCountResponse(true, logRepository.deleteOlderThan(principal.tenantId(), days));
    }
}
