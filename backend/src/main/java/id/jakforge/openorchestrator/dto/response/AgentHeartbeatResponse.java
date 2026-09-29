package id.jakforge.openorchestrator.dto.response;

import java.util.List;
import java.util.Map;

/**
 * Jawaban POST /api/agent/heartbeat.
 *
 * @param commands        StopJob / KillJob — diturunkan dari keadaan job, jadi dikirim ulang di
 *                        setiap denyut sampai job-nya selesai; agent memperlakukan perintah yang
 *                        sama berulang sebagai satu perintah
 * @param settingsVersion berubah = agent mengambil setelannya lagi lewat login
 */
public record AgentHeartbeatResponse(String serverTime, List<Map<String, Object>> commands, int settingsVersion,
                                     int heartbeatSeconds, int heartbeatBusySeconds) {
}
