package id.jakforge.openorchestrator.dto.response;

import java.util.List;
import java.util.Map;

/**
 * Jawaban POST /api/agent/login.
 *
 * @param robots   robot yang dilayani agent ini — tanpa sandi Windows-nya
 * @param settings jeda denyut, lease, versi minimal, dan versi setelan
 */
public record AgentLoginResponse(String token, String expiresAt, Map<String, Object> machine,
                                 List<Map<String, Object>> robots, Map<String, Object> settings) {
}
