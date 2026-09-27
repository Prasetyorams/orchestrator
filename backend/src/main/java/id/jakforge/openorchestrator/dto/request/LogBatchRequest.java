package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.RequestBodies;

import java.util.List;
import java.util.Map;

/**
 * Kiriman catatan berkelompok dari robot (POST /api/logs).
 *
 * <p>Isinya diserahkan apa adanya ke layanan: bentuk tiap baris bermacam-macam
 * antar versi robot, dan layanannya yang memutuskan baris mana yang bisa
 * dipakai — baris yang cacat dilewati, bukan menggagalkan seluruh kiriman.
 *
 * @param lines baris mentah; null kalau medan {@code lines} tidak ada atau bukan larik
 */
public record LogBatchRequest(List<?> lines) {

    public static LogBatchRequest fromBody(Map<String, Object> body) {
        return new LogBatchRequest(RequestBodies.valueOf(body, "lines") instanceof List<?> lines ? lines : null);
    }
}
