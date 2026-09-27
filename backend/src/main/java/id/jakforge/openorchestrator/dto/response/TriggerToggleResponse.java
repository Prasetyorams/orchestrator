package id.jakforge.openorchestrator.dto.response;

/** Jawaban menyalakan atau mematikan pemicu: keadaannya yang baru. */
public record TriggerToggleResponse(boolean ok, boolean enabled) {
}
