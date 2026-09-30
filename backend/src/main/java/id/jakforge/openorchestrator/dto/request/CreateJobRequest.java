package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.RequestBodies;

import java.math.BigDecimal;
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
 * @param folderId       folder prosesnya, apa adanya dari badan — haknya diperiksa
 *                       layanan. Studio tidak mengirimnya.
 * @param robotName      robot yang diminta (Akun di Start Job); kosong = robot mana pun di folder
 * @param machineName    mesin yang diminta; kosong = mesin mana pun
 * @param priority       Low, Normal, High, atau Inherited; kosong berarti Inherited — prioritas
 *                       bawaan prosesnya, yang Normal selama belum diubah
 * @param runtimeType    tipe runtime yang diminta; kosong = runtime mana pun
 * @param count          berapa kali prosesnya dijalankan ("Execute the process N times"); null = 1
 * @param countMalformed {@code count} dikirim tapi bukan bilangan bulat — "1.5", "-", "dua"
 */
public record CreateJobRequest(
        String processName,
        String robotName,
        String machineName,
        String source,
        String priority,
        String inputJson,
        String folderId,
        String runtimeType,
        Integer count,
        boolean countMalformed) {

    static final String DEFAULT_SOURCE = "Manual";

    public static CreateJobRequest fromBody(Map<String, Object> body) {
        Object rawCount = RequestBodies.valueOf(body, "count");
        Integer count = strictInteger(rawCount);

        return new CreateJobRequest(
                RequestBodies.trimmedText(body, "processName"),
                RequestBodies.trimmedText(body, "robotName"),
                RequestBodies.trimmedText(body, "machineName"),
                RequestBodies.text(body, "source", DEFAULT_SOURCE),
                RequestBodies.trimmedText(body, "priority"),
                RequestBodies.text(body, "inputJson"),
                RequestBodies.text(body, "folderId"),
                RequestBodies.trimmedText(body, "runtimeType"),
                count,
                rawCount != null && count == null);
    }

    /**
     * Bilangan bulat yang BENAR-BENAR bulat: 3 dan "3" diterima; 1.5, "1.5",
     * dan "tiga" tidak. Membulatkan diam-diam berarti menjalankan proses lebih
     * sering atau lebih jarang dari yang diminta.
     */
    static Integer strictInteger(Object value) {
        if (value == null) return null;

        try {
            BigDecimal number = value instanceof Number n ? new BigDecimal(n.toString())
                    : new BigDecimal(String.valueOf(value).trim());

            return number.stripTrailingZeros().scale() <= 0 ? number.intValueExact() : null;
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
