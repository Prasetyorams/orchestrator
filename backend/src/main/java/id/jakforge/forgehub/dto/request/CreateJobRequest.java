package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.RequestBodies;

import java.util.Map;

/**
 * Permintaan menjadwalkan pekerjaan (POST /api/jobs).
 *
 * <p>Dibuat dari {@code Map}, bukan dipetakan langsung oleh Jackson sebagai
 * {@code @RequestBody}. Alasannya bukan gaya: Studio (activity Start Job) dan
 * dasbor mengirim susunan yang sedikit berbeda untuk hal yang sama, dan record
 * yang ketat akan menolak permintaan yang hari ini berhasil. Yang terlihat di
 * sisi robot hanyalah "400 Bad Request" tanpa petunjuk medan mana yang
 * mengganggu.
 *
 * <p>Jadi: longgar di tepi, bertipe begitu masuk ke dalam.
 *
 * @param folderId folder prosesnya, apa adanya dari badan — haknya diperiksa
 *                 layanan. Studio tidak mengirimnya.
 */
public record CreateJobRequest(
        String processName,
        String robotName,
        String machineName,
        String source,
        String priority,
        String inputJson,
        String folderId) {

    static final String DEFAULT_SOURCE = "Manual";
    static final String DEFAULT_PRIORITY = "Normal";

    public static CreateJobRequest fromBody(Map<String, Object> body) {
        return new CreateJobRequest(
                RequestBodies.trimmedText(body, "processName"),
                RequestBodies.text(body, "robotName"),
                RequestBodies.text(body, "machineName"),
                RequestBodies.text(body, "source", DEFAULT_SOURCE),
                RequestBodies.text(body, "priority", DEFAULT_PRIORITY),
                RequestBodies.text(body, "inputJson"),
                RequestBodies.text(body, "folderId"));
    }
}
