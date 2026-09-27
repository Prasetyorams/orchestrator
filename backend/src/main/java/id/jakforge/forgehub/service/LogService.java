package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.common.PageLimits;
import id.jakforge.forgehub.common.RequestBodies;
import id.jakforge.forgehub.common.Uuids;
import id.jakforge.forgehub.dto.request.LogBatchRequest;
import id.jakforge.forgehub.dto.response.DeletedCountResponse;
import id.jakforge.forgehub.dto.response.LogWriteResponse;
import id.jakforge.forgehub.model.LogLevel;
import id.jakforge.forgehub.model.Severity;
import id.jakforge.forgehub.repository.AlertRepository;
import id.jakforge.forgehub.repository.LogRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    private static final String ALERT_SOURCE = "logs";
    private static final String UNKNOWN_ROBOT = "robot";

    private final LogRepository logRepository;
    private final AlertRepository alertRepository;
    private final FolderAccessService folderAccessService;

    /**
     * @param levels tingkat yang ingin dilihat — satu atau lebih, dari
     *               {@code ?level=INFO,WARN} maupun {@code ?level=INFO&level=WARN}.
     *               Kosong berarti semua tingkat.
     */
    public List<Map<String, Object>> search(ForgeHubPrincipal principal, List<String> levels, String robotName,
                                            String processName, String jobId, String folderId, Integer limit) {
        UUID folder = folderAccessService.resolveFolderFilter(principal, folderId);
        UUID job = null;

        if (jobId != null && !jobId.isBlank()) {
            job = Uuids.parseOrNull(jobId);

            // Id yang bukan UUID tidak akan pernah cocok dengan apa pun. Yang
            // dikembalikan daftar kosong, bukan galat penguraian dari basis data.
            if (job == null) return List.of();
        }

        Set<LogLevel> requestedLevels = parseRequestedLevels(levels);

        // Tingkat rincian tidak pernah ditampilkan (lihat LogLevel.isVerbose).
        // Yang HANYA meminta rincian memang tidak mendapat apa-apa; yang
        // memintanya bersama tingkat lain mendapat tingkat lain itu saja.
        if (!requestedLevels.isEmpty() && requestedLevels.stream().allMatch(LogLevel::isVerbose)) return List.of();

        Set<String> storedSpellings = new LinkedHashSet<>();

        for (LogLevel level : requestedLevels) {
            if (!level.isVerbose()) storedSpellings.addAll(level.storedSpellings());
        }

        return logRepository.search(principal.tenantId(), storedSpellings, robotName, processName, job, folder,
                PageLimits.clamp(limit, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE));
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
    public LogWriteResponse write(ForgeHubPrincipal principal, LogBatchRequest request) {
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

            logRepository.insert(tenantId, level, message,
                    RequestBodies.text(line, "robotName"), RequestBodies.text(line, "machineName"),
                    RequestBodies.text(line, "processName"), Uuids.parseOrNull(RequestBodies.text(line, "jobId")),
                    RequestBodies.text(line, "loggedAt"));

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
    public DeletedCountResponse purgeOlderThan(ForgeHubPrincipal principal, Integer days) {
        if (days == null || days < MIN_PURGE_AGE_DAYS) {
            throw ApiException.badRequest("Parameter 'olderThanDays' wajib diisi dan minimal 1.");
        }

        return new DeletedCountResponse(true, logRepository.deleteOlderThan(principal.tenantId(), days));
    }
}
