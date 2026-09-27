package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.RequestBodies;

import java.util.Map;

/**
 * Masuk (POST /api/auth/login).
 *
 * <p>Dibaca longgar: Studio, JakRunner, dan activity Orchestrator semuanya
 * masuk lewat sini, dengan klien HTTP masing-masing.
 */
public record LoginRequest(String username, String password) {

    public static LoginRequest fromBody(Map<String, Object> body) {
        return new LoginRequest(RequestBodies.trimmedText(body, "username"), RequestBodies.text(body, "password"));
    }
}
