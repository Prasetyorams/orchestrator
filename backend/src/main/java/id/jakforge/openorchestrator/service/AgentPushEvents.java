package id.jakforge.openorchestrator.service;

import java.util.UUID;

/**
 * Kejadian yang membuat Robot Agent perlu bertindak lebih cepat daripada
 * jadwal pollingnya. Diterbitkan layanan job; {@link AgentPushService}
 * meneruskannya sebagai PETUNJUK lewat WebSocket sesudah transaksinya selesai.
 *
 * <p>Petunjuk saja: job tetap diambil lewat klaim dan perintah tetap dibaca
 * dari jawaban denyut. Petunjuk yang tidak sampai hanya memperlambat.
 */
public final class AgentPushEvents {

    private AgentPushEvents() {
    }

    /** Job baru PENDING: Start Job, jalankan ulang, pemicu, atau percobaan ulang otomatis. */
    public record JobQueued(UUID jobId) {
    }

    /** Stop, Kill, Pause, atau Resume diminta untuk job yang dipegang robot. */
    public record CommandRequested(UUID jobId, String command) {
    }
}
