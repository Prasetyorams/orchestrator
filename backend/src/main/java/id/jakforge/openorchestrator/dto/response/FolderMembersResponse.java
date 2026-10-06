package id.jakforge.openorchestrator.dto.response;

import java.util.List;
import java.util.Map;

/**
 * Pengguna dan robot yang ditugaskan ke sebuah folder, dan siapa yang boleh
 * mengaturnya. {@code canManageMachines}: boleh mendaftarkan dan mengeluarkan
 * mesin folder ini (V12) — aturannya sama dengan robot.
 */
public record FolderMembersResponse(
        Map<String, Object> folder,
        List<Map<String, Object>> users,
        List<Map<String, Object>> robots,
        boolean canManageUsers,
        boolean canManageRobots,
        boolean canManageMachines) {
}
