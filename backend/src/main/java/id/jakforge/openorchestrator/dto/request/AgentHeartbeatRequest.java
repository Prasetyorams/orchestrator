package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.RequestBodies;
import id.jakforge.openorchestrator.common.Uuids;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Denyut Robot Agent (POST /api/agent/heartbeat): satu per mesin, berisi
 * semua robotnya. Dibaca longgar — agent yang lebih baru boleh mengirim medan
 * yang belum dikenal.
 */
public record AgentHeartbeatRequest(String agentVersion, Double cpuPercent, Double memoryUsedMb, Double memoryTotalMb,
                                    Integer maxInteractiveSessions, List<RobotReport> robots) {

    /**
     * Keadaan satu robot.
     *
     * @param sessionReady  menurut AGENT: bisakah ia menjalankan job sekarang — sesi yang terkunci
     *                      tetap "Active" bagi WTS, jadi siap-tidaknya tidak bisa ditebak dari
     *                      {@code sessionState}
     * @param activeJobIds  job yang belum selesai dilaporkan: masih berjalan, ATAU laporan akhirnya
     *                      masih di outbox agent. Medan lama {@code runningJobIds} diterima dengan
     *                      arti yang sama.
     * @param jobIdsSent    apakah agent mengirim daftar itu sama sekali; tanpa daftar, rekonsiliasi
     *                      tidak dijalankan — ketiadaan medan bukan berarti "tidak ada job"
     */
    public record RobotReport(UUID robotId, String state, Integer sessionId, String sessionState, String windowsUser,
                              Boolean sessionReady, String reasonCode, String reasonText, String executorState,
                              Integer executorPid, Set<UUID> activeJobIds, boolean jobIdsSent) {
    }

    @SuppressWarnings("unchecked")
    public static AgentHeartbeatRequest fromBody(Map<String, Object> body) {
        Map<String, Object> machine = RequestBodies.object(body, "machine");
        List<RobotReport> robots = new ArrayList<>();

        for (Object item : RequestBodies.list(body, "robots")) {
            if (!(item instanceof Map<?, ?> map)) continue;

            Map<String, Object> robot = (Map<String, Object>) map;
            UUID robotId = Uuids.parseOrNull(RequestBodies.text(robot, "robotId"));
            if (robotId == null) continue;

            Map<String, Object> session = RequestBodies.object(robot, "session");
            Map<String, Object> reason = RequestBodies.object(robot, "reason");
            Map<String, Object> executor = RequestBodies.object(robot, "executor");

            Object ids = RequestBodies.valueOf(robot, "activeJobIds");
            if (ids == null) ids = RequestBodies.valueOf(robot, "runningJobIds");

            Set<UUID> jobIds = new LinkedHashSet<>();
            if (ids instanceof List<?> list) {
                for (Object id : list) {
                    UUID jobId = id == null ? null : Uuids.parseOrNull(String.valueOf(id));
                    if (jobId != null) jobIds.add(jobId);
                }
            }

            robots.add(new RobotReport(
                    robotId,
                    RequestBodies.trimmedText(robot, "state"),
                    RequestBodies.optionalInteger(session, "id"),
                    RequestBodies.trimmedText(session, "state"),
                    RequestBodies.trimmedText(session, "windowsUser"),
                    RequestBodies.optionalBool(session, "ready"),
                    firstNonNull(RequestBodies.trimmedText(reason, "code"), RequestBodies.trimmedText(robot, "reasonCode")),
                    firstNonNull(RequestBodies.trimmedText(reason, "text"), RequestBodies.trimmedText(robot, "reasonText")),
                    RequestBodies.trimmedText(executor, "state"),
                    RequestBodies.optionalInteger(executor, "pid"),
                    jobIds,
                    ids instanceof List<?>));
        }

        return new AgentHeartbeatRequest(
                RequestBodies.trimmedText(body, "agentVersion"),
                RequestBodies.optionalDecimal(machine, "cpuPercent"),
                RequestBodies.optionalDecimal(machine, "memoryUsedMb"),
                RequestBodies.optionalDecimal(machine, "memoryTotalMb"),
                RequestBodies.optionalInteger(body, "maxInteractiveSessions"),
                robots);
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
