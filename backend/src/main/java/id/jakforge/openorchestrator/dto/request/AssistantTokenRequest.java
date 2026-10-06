package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.RequestBodies;

import java.util.Map;

/**
 * Open Assistant menukar kode sekali pakai dengan token. Dibaca longgar,
 * seperti endpoint robot lain: versi Open Assistant yang lebih baru boleh
 * mengirim medan yang belum dikenal di sini.
 */
public record AssistantTokenRequest(String code, String codeVerifier, String machineName, String clientVersion) {

    public static AssistantTokenRequest fromBody(Map<String, Object> body) {
        return new AssistantTokenRequest(
                RequestBodies.trimmedText(body, "code"),
                RequestBodies.trimmedText(body, "codeVerifier"),
                RequestBodies.trimmedText(body, "machineName"),
                RequestBodies.trimmedText(body, "clientVersion"));
    }
}
