package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.RequestBodies;

import java.util.Map;

/**
 * Robot Agent masuk dengan machine key (POST /api/agent/login), dibaca longgar.
 *
 * @param machineName            nama komputer menurut agent — dibandingkan dengan nama mesinnya
 * @param maxInteractiveSessions batas sesi interaktif Windows di mesin itu (1 untuk Windows 10/11)
 */
public record AgentLoginRequest(String machineKey, String machineName, String agentVersion, String os,
                                Integer maxInteractiveSessions) {

    public static AgentLoginRequest fromBody(Map<String, Object> body) {
        return new AgentLoginRequest(
                RequestBodies.trimmedText(body, "machineKey"),
                RequestBodies.trimmedText(body, "machineName"),
                RequestBodies.trimmedText(body, "agentVersion"),
                RequestBodies.trimmedText(body, "os"),
                RequestBodies.optionalInteger(body, "maxInteractiveSessions"));
    }
}
