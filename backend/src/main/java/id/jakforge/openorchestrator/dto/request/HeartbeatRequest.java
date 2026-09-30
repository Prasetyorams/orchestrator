package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.RequestBodies;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.model.JobCommands;
import id.jakforge.openorchestrator.model.RobotStatus;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Denyut dari robot (POST /api/robots/{name}/heartbeat).
 *
 * <p>Dibaca longgar lewat {@link RequestBodies}: dikirim JakRunner, bukan
 * dasbor.
 *
 * <p>machineName boleh null, dan itu berarti "tidak disebut kali ini" —
 * bukan "kosongkan". Repository memakai COALESCE untuk menjaga bedanya.
 *
 * @param pausedJobId job yang sedang ditahan robot; null berarti tidak ada yang dijeda
 * @param pauseSource siapa yang menjedanya: "dashboard" (karena PauseJob) atau "local" (tombol
 *                    Jeda di PC robot). Dibakukan; yang tidak dikenal dianggap "local", karena
 *                    jeda lokal tidak pernah dilanjutkan server.
 */
public record HeartbeatRequest(String status, double cpuPercent, double memoryMb, String machineName,
                               UUID pausedJobId, String pauseSource) {

    /** Bentuk lama, tanpa jeda. */
    public HeartbeatRequest(String status, double cpuPercent, double memoryMb, String machineName) {
        this(status, cpuPercent, memoryMb, machineName, null, null);
    }

    public static HeartbeatRequest fromBody(Map<String, Object> body) {
        UUID pausedJobId = Uuids.parseOrNull(RequestBodies.trimmedText(body, "pausedJobId"));

        return new HeartbeatRequest(
                RequestBodies.text(body, "status", RobotStatus.AVAILABLE.name()).toUpperCase(Locale.ROOT),
                RequestBodies.decimal(body, "cpuPercent", 0),
                RequestBodies.decimal(body, "memoryMb", 0),
                RequestBodies.text(body, "machineName"),
                pausedJobId,
                pausedJobId == null ? null : JobCommands.normalizeSource(RequestBodies.text(body, "pauseSource")));
    }
}
