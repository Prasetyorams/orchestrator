package id.jakforge.openorchestrator.dto.response;

/**
 * Akun Windows robot untuk SATU penyiapan sesi (POST /api/agent/jobs/{id}/windows-credential).
 *
 * <p>Tidak pernah dicatat, disimpan agent, atau ditampilkan di tempat lain.
 */
public record WindowsCredentialResponse(String username, String password) {
}
