package id.jakforge.forgehub.dto;

import id.jakforge.forgehub.common.Badan;

import java.util.Map;

/**
 * Permintaan membuat atau menyunting pemicu.
 *
 * <p>Dua cara menjadwalkan, dan keduanya ada karena tidak saling menggantikan:
 * SELANG ("tiap 15 menit") tidak menuntut siapa pun menulis cron, sedangkan
 * CRON bisa menyatakan "tiap hari kerja pukul 07:00" — yang tidak bisa
 * dinyatakan sebagai selang menit sama sekali.
 *
 * <p>Kalau keduanya diisi, cron yang menang; {@link #pakaiCron()} yang
 * memutuskan, dan hanya di satu tempat ini.
 */
public record TriggerRequest(
        String name,
        String processName,
        String robotName,
        String type,
        String cron,
        int intervalMinutes,
        String priority,
        String timezone,
        String runtimeType,
        boolean enabled) {

    public static TriggerRequest dari(Map<String, Object> body) {
        String cron = Badan.nama(body, "cron");

        return new TriggerRequest(
                Badan.nama(body, "name"),
                Badan.nama(body, "processName"),
                Badan.teks(body, "robotName"),
                Badan.teks(body, "type", cron != null ? "Cron" : "Time"),
                cron,
                Badan.bulat(body, "intervalMinutes", 60),
                Badan.teks(body, "priority", "Normal"),
                Badan.teks(body, "timezone", "UTC"),
                Badan.teks(body, "runtimeType", "Unattended"),
                Badan.benar(body, "enabled", true));
    }

    public boolean pakaiCron() {
        return cron != null && !cron.isBlank();
    }
}
