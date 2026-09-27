package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.RequestBodies;
import id.jakforge.openorchestrator.model.RobotStatus;

import java.util.Locale;
import java.util.Map;

/**
 * Denyut dari robot (POST /api/robots/{name}/heartbeat).
 *
 * <p>Dibaca longgar lewat {@link RequestBodies}: dikirim JakRunner, bukan
 * dasbor.
 *
 * <p>machineName boleh null, dan itu berarti "tidak disebut kali ini" —
 * bukan "kosongkan". Repository memakai COALESCE untuk menjaga bedanya.
 */
public record HeartbeatRequest(String status, double cpuPercent, double memoryMb, String machineName) {

    public static HeartbeatRequest fromBody(Map<String, Object> body) {
        return new HeartbeatRequest(
                RequestBodies.text(body, "status", RobotStatus.AVAILABLE.name()).toUpperCase(Locale.ROOT),
                RequestBodies.decimal(body, "cpuPercent", 0),
                RequestBodies.decimal(body, "memoryMb", 0),
                RequestBodies.text(body, "machineName"));
    }
}
