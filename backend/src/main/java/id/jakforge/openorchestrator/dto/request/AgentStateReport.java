package id.jakforge.openorchestrator.dto.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.jakforge.openorchestrator.common.RequestBodies;

import java.util.List;
import java.util.Map;

/**
 * Laporan keadaan job dari Robot Agent (POST /api/agent/jobs/{id}/state).
 *
 * <p>{@code outputJson} boleh dikirim sebagai untai JSON (seperti v1) atau
 * langsung sebagai objek; keduanya disimpan sebagai teks JSON.
 *
 * @param seq nomor urut laporan per job, mulai 1; kiriman ulang memakai nomor yang sama
 */
public record AgentStateReport(Long seq, String state, Integer progress, String info, String errorCode,
                               String outputJson, Integer sessionId, String windowsUser, Integer executorPid) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static AgentStateReport fromBody(Map<String, Object> body) {
        Map<String, Object> context = RequestBodies.object(body, "context");

        return new AgentStateReport(
                RequestBodies.optionalLong(body, "seq"),
                RequestBodies.trimmedText(body, "state"),
                RequestBodies.optionalInteger(body, "progress"),
                RequestBodies.text(body, "info"),
                RequestBodies.trimmedText(body, "errorCode"),
                jsonText(RequestBodies.valueOf(body, "outputJson")),
                RequestBodies.optionalInteger(context, "sessionId"),
                RequestBodies.trimmedText(context, "windowsUser"),
                RequestBodies.optionalInteger(context, "executorPid"));
    }

    private static String jsonText(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text;

        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                return null;
            }
        }

        return String.valueOf(value);
    }
}
