package id.jakforge.openorchestrator.dto.response;

import java.util.Map;

/**
 * Halaman Setelan.
 *
 * @param database "PostgreSQL" — bukan jalur folder: datanya ada di basis data, bukan di sebuah folder
 * @param counts   jumlah isi tiap tabel
 */
public record SettingsResponse(
        String tenant,
        String serverTime,
        String displayTimezone,
        long robotOfflineAfterSeconds,
        long tokenLifetimeHours,
        String database,
        Map<String, Object> counts) {
}
