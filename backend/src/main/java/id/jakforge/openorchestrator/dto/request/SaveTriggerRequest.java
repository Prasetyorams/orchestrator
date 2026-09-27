package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import id.jakforge.openorchestrator.model.TriggerDefinition;
import jakarta.validation.constraints.NotNull;

/**
 * Membuat atau menyunting pemicu (POST /api/triggers).
 *
 * <p>Dua cara menjadwalkan, dan keduanya ada karena tidak saling menggantikan:
 * SELANG ("tiap 15 menit") tidak menuntut siapa pun menulis cron, sedangkan
 * CRON bisa menyatakan "tiap hari kerja pukul 07:00" — yang tidak bisa
 * dinyatakan sebagai selang menit sama sekali.
 *
 * <p>Kalau keduanya diisi, cron yang menang; {@link #usesCron()} yang
 * memutuskan, dan hanya di satu tempat ini. Bentuk cron, selang, dan zona
 * waktunya diperiksa layanan.
 *
 * @param folderId folder yang sedang dibuka; kosong berarti proses bernama itu
 *                 di mana pun ia berada
 */
public record SaveTriggerRequest(
        @NotNull(message = "Nama pemicu wajib diisi.")
        String name,

        @NotNull(message = "processName wajib diisi.")
        String processName,

        String robotName,
        String type,
        String cron,
        Integer intervalMinutes,
        String priority,
        String timezone,
        String runtimeType,
        Boolean enabled,
        String folderId) {

    static final String CRON_TYPE = "Cron";
    static final String INTERVAL_TYPE = "Time";
    static final int DEFAULT_INTERVAL_MINUTES = 60;
    static final String DEFAULT_PRIORITY = "Normal";
    public static final String DEFAULT_TIMEZONE = "UTC";
    static final String DEFAULT_RUNTIME_TYPE = "Unattended";

    public SaveTriggerRequest {
        name = Strings.trimToNull(name);
        processName = Strings.trimToNull(processName);
        robotName = Strings.emptyToNull(robotName);
        cron = Strings.trimToNull(cron);
        type = Strings.defaultIfEmpty(type, cron != null ? CRON_TYPE : INTERVAL_TYPE);
        intervalMinutes = intervalMinutes == null ? DEFAULT_INTERVAL_MINUTES : intervalMinutes;
        priority = Strings.defaultIfEmpty(priority, DEFAULT_PRIORITY);
        timezone = Strings.defaultIfEmpty(timezone, DEFAULT_TIMEZONE);
        runtimeType = Strings.defaultIfEmpty(runtimeType, DEFAULT_RUNTIME_TYPE);
        enabled = enabled == null || enabled;
        folderId = Strings.emptyToNull(folderId);
    }

    public boolean usesCron() {
        return cron != null && !cron.isBlank();
    }

    /** Isi pemicu yang disimpan, tanpa folder (folder diputuskan layanan). */
    public TriggerDefinition toDefinition() {
        return new TriggerDefinition(name, processName, robotName, type, cron, intervalMinutes, enabled,
                priority, timezone, runtimeType);
    }
}
