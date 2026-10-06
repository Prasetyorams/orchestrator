package id.jakforge.openorchestrator.dto.response;

/**
 * Kode sekali pakai, sudah dalam bentuk tautan yang dibuka peramban:
 * {@code openassistant://signin?code=...&state=...}.
 */
public record AssistantCodeResponse(String redirectUrl, String expiresAt) {
}
