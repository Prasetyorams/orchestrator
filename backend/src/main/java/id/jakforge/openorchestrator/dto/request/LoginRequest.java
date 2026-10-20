package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.RequestBodies;

import java.util.Map;

/**
 * Masuk (POST /api/auth/login).
 *
 * <p>Dibaca longgar: Studio, JakRunner, dan activity Orchestrator semuanya
 * masuk lewat sini, dengan klien HTTP masing-masing.
 *
 * @param client "dashboard" untuk dasbor (V18): tokennya terikat ke sesi dasbor
 *               dengan batas waktu menganggur. Selain itu (Studio, robot, alat
 *               lain — yang tidak mengirimnya) token biasa, seperti dulu.
 */
public record LoginRequest(String username, String password, String client) {

    public LoginRequest(String username, String password) {
        this(username, password, null);
    }

    public static LoginRequest fromBody(Map<String, Object> body) {
        return new LoginRequest(RequestBodies.trimmedText(body, "username"), RequestBodies.text(body, "password"),
                RequestBodies.trimmedText(body, "client"));
    }
}
