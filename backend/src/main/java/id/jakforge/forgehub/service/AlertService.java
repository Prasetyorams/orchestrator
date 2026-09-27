package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.common.PageLimits;
import id.jakforge.forgehub.dto.response.AlertSummaryResponse;
import id.jakforge.forgehub.dto.response.ChangedCountResponse;
import id.jakforge.forgehub.model.Severity;
import id.jakforge.forgehub.repository.AlertRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Aturan tentang peringatan: daftar, lonceng, dan tanda sudah dibaca. */
@Service
@RequiredArgsConstructor
public class AlertService {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 500;

    /** Jumlah peringatan terbaru di lonceng bilah atas. */
    static final int SUMMARY_SIZE = 8;

    private final AlertRepository alertRepository;

    /**
     * @param unread     "1" atau "true" berarti hanya yang belum dibaca
     * @param severities tingkat yang ingin dilihat — Info, Warning, Error; satu
     *                   atau lebih. Kosong berarti semua tingkat.
     */
    public List<Map<String, Object>> findAll(ForgeHubPrincipal principal, String unread, List<String> severities,
                                             Integer limit) {
        // Menerima "1" maupun "true": dasbor lama mengirim "1", dan klien lain
        // yang menulis "true" tidak boleh diam-diam melihat seluruh daftar.
        boolean unreadOnly = "1".equals(unread) || "true".equalsIgnoreCase(unread);

        return alertRepository.findRecent(principal.tenantId(), unreadOnly, parseSeverities(severities),
                PageLimits.clamp(limit, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE));
    }

    /** Sama seperti {@link LogService#parseRequestedLevels}, untuk tingkat peringatan. */
    static List<String> parseSeverities(List<String> rawSeverities) {
        Set<Severity> severities = EnumSet.noneOf(Severity.class);
        if (rawSeverities == null) return List.of();

        for (String rawSeverity : rawSeverities) {
            if (rawSeverity == null) continue;

            for (String text : rawSeverity.split(",")) {
                if (text.isBlank()) continue;

                Severity severity = Severity.parse(text);

                if (severity == null) {
                    throw ApiException.badRequest("Tingkat peringatan tidak dikenal: '" + text.trim() + "'.");
                }

                severities.add(severity);
            }
        }

        return severities.stream().map(Severity::storedValue).toList();
    }

    public ChangedCountResponse markRead(ForgeHubPrincipal principal, long alertId) {
        return new ChangedCountResponse(true, alertRepository.markRead(principal.tenantId(), alertId));
    }

    public ChangedCountResponse markAllRead(ForgeHubPrincipal principal) {
        return new ChangedCountResponse(true, alertRepository.markAllRead(principal.tenantId()));
    }

    /**
     * Isi lonceng di bilah atas: jumlah yang belum dibaca dan beberapa yang
     * terbaru. Terpisah dari dasbor supaya lonceng bekerja di halaman mana
     * pun — dasbor milik satu folder, sedangkan peringatan milik penyewa.
     */
    public AlertSummaryResponse getSummary(ForgeHubPrincipal principal) {
        return new AlertSummaryResponse(
                alertRepository.countUnread(principal.tenantId()),
                alertRepository.findRecent(principal.tenantId(), false, SUMMARY_SIZE));
    }
}
