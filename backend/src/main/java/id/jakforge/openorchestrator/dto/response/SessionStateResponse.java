package id.jakforge.openorchestrator.dto.response;

/**
 * Keadaan sesi pemanggil (GET /api/auth/session, POST /api/auth/session/activity).
 *
 * @param kind          "dashboard" — sesi dasbor, batas waktu menganggurnya DITEGAKKAN server; "token" —
 *                      token tanpa sesi (Studio, robot, alat lain, token dasbor dari sebelum V18): server hanya
 *                      membatasi umurnya, dan idleTimeout* hanyalah kebijakan penyewa untuk diterapkan klien
 * @param idleExpiresAt kapan sesi berakhir kalau tidak ada aktivitas lagi; null kalau batasnya mati
 * @param expiresAt     batas mutlak (umur token)
 * @param serverTime    jam server saat menjawab, supaya klien bisa menghitung selisih jamnya sendiri
 */
public record SessionStateResponse(String kind, boolean idleTimeoutEnabled, int idleTimeoutMinutes,
                                   int warningBeforeLogoutMinutes, String lastActivityAt, String idleExpiresAt,
                                   String expiresAt, String serverTime) {
}
