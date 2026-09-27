package id.jakforge.openorchestrator.dto.response;

import java.util.List;

/** Satu baris matriks izin di layar Peran: sumber dan tindakan yang bisa dipilih. */
public record PermissionResourceResponse(String resource, List<String> actions) {
}
