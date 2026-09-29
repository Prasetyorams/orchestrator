package id.jakforge.openorchestrator.dto.response;

/**
 * Machine key yang baru dibuat. Ini SATU-SATUNYA saat kuncinya terlihat;
 * Orchestrator hanya menyimpan hash-nya.
 */
public record MachineKeyResponse(String machineKey, String keyPrefix) {
}
