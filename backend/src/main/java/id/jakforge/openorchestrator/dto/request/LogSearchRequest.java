package id.jakforge.openorchestrator.dto.request;

import java.util.List;
import java.util.Map;

/**
 * Saringan halaman Catatan (GET /api/logs, /api/logs/filters, /api/logs/export),
 * apa adanya dari parameter alamat — diperiksa dan diterjemahkan layanan.
 *
 * @param levels  tingkat, satu atau lebih: {@code ?level=WARN,ERROR} atau parameter berulang
 * @param machine nama mesin, persis
 * @param host    host identity (akun Windows robot), persis
 * @param time    rentang waktu: 15m, 30m, 1h, 6h, 12h, 24h, today, yesterday, 7d, atau custom
 * @param from    awal rentang custom, ISO-8601; tanpa zona = zona tampilan
 * @param to      akhir rentang custom (tidak termasuk)
 * @param q       potongan teks yang dicari di pesan
 * @param trigger pemicu jalan (V14): job, manual, atau local-schedule
 */
public record LogSearchRequest(
        List<String> levels,
        String robot,
        String process,
        String jobId,
        String folderId,
        Integer limit,
        String machine,
        String host,
        String time,
        String from,
        String to,
        String q,
        String trigger) {

    /** Bentuk lama: tanpa mesin, host identity, waktu, dan teks. */
    public static LogSearchRequest of(List<String> levels, String robot, String process, String jobId,
                                      String folderId, Integer limit) {
        return new LogSearchRequest(levels, robot, process, jobId, folderId, limit, null, null, null, null, null,
                null, null);
    }

    /**
     * Dari parameter alamat. {@code level} boleh berulang; parameter lain yang
     * berulang diambil yang pertama. {@code limit} yang bukan bilangan
     * diabaikan — batas bawaan yang berlaku, bukan galat.
     */
    public static LogSearchRequest fromParams(Map<String, List<String>> params) {
        return new LogSearchRequest(
                params.getOrDefault("level", List.of()),
                first(params, "robot"),
                first(params, "process"),
                first(params, "jobId"),
                first(params, "folderId"),
                integerOrNull(first(params, "limit")),
                first(params, "machine"),
                first(params, "host"),
                first(params, "time"),
                first(params, "from"),
                first(params, "to"),
                first(params, "q"),
                first(params, "trigger"));
    }

    private static String first(Map<String, List<String>> params, String name) {
        List<String> values = params.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static Integer integerOrNull(String text) {
        if (text == null || text.isBlank()) return null;

        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
