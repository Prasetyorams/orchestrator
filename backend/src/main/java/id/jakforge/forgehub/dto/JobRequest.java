package id.jakforge.forgehub.dto;

import id.jakforge.forgehub.common.Badan;

import java.util.Map;

/**
 * Permintaan menjadwalkan pekerjaan.
 *
 * <p>Dibuat dari {@code Map}, bukan dipetakan langsung oleh Jackson sebagai
 * {@code @RequestBody}. Alasannya bukan gaya: Studio, JakRunner, dan dasbor
 * mengirim susunan yang sedikit berbeda untuk hal yang sama, dan record yang
 * ketat akan menolak permintaan yang hari ini berhasil. Yang terlihat di sisi
 * robot hanyalah "400 Bad Request" tanpa petunjuk medan mana yang mengganggu.
 *
 * <p>Jadi: longgar di tepi, bertipe begitu masuk ke dalam.
 */
public record JobRequest(
        String processName,
        String robotName,
        String machineName,
        String source,
        String priority,
        String inputJson) {

    public static JobRequest dari(Map<String, Object> body) {
        return new JobRequest(
                Badan.nama(body, "processName"),
                Badan.teks(body, "robotName"),
                Badan.teks(body, "machineName"),
                Badan.teks(body, "source", "Manual"),
                Badan.teks(body, "priority", "Normal"),
                Badan.teks(body, "inputJson"));
    }
}
