package id.jakforge.openorchestrator.dto.response;

import java.util.List;

/**
 * Tenant › Setelan › Sesi & Keamanan (V18): kebijakan batas waktu menganggur
 * sesi dasbor, beserta batas yang boleh dipilih.
 *
 * @param warningBeforeLogoutMinutes 0 = tanpa peringatan sebelum keluar
 * @param updatedAt                  null kalau belum pernah diubah (masih nilai bawaan)
 * @param minMinutes                 durasi terpendek yang diterima server
 * @param maxMinutes                 durasi terpanjang — tidak lebih lama dari umur token
 * @param required                   kebijakan server melarang mematikan batas waktunya
 * @param tokenLifetimeMinutes       umur token: batas mutlak sesi, terpisah dari batas menganggur
 */
public record SessionTimeoutSettingsResponse(boolean idleTimeoutEnabled, int idleTimeoutMinutes,
                                             int warningBeforeLogoutMinutes, String updatedAt, String updatedBy,
                                             int minMinutes, int maxMinutes, boolean required,
                                             long tokenLifetimeMinutes, List<Integer> durationOptions,
                                             List<Integer> warningOptions) {
}
