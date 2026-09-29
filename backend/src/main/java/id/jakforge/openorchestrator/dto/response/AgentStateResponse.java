package id.jakforge.openorchestrator.dto.response;

/**
 * Jawaban POST /api/agent/jobs/{id}/state: keadaan job SESUDAH laporannya diterapkan.
 *
 * @param stopRequested ada yang menekan Stop — agent sebaiknya berhenti tanpa menunggu denyut berikutnya
 */
public record AgentStateResponse(String state, boolean stopRequested) {
}
