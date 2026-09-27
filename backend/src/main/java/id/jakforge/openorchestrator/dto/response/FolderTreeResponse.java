package id.jakforge.openorchestrator.dto.response;

import java.util.List;
import java.util.Map;

/**
 * Isi bilah folder.
 *
 * @param folders   folder bersama yang boleh dilihat, beserta leluhurnya ({@code accessible: false})
 * @param personal  Folder Saya, atau null kalau belum pernah dibuka
 * @param canManage boleh membuat folder
 */
public record FolderTreeResponse(List<Map<String, Object>> folders, Map<String, Object> personal,
                                 boolean canManage) {
}
