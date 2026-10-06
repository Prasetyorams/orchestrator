package id.jakforge.openorchestrator.dto.request;

import java.util.List;

/**
 * Mesin yang didaftarkan ke folder: satu ({@code machineId}, POST
 * /api/folders/{id}/machines) atau beberapa sekaligus ({@code machineIds},
 * POST /api/folders/{id}/machines/bulk).
 */
public record FolderMachinesRequest(String machineId, List<String> machineIds) {
}
