package id.jakforge.openorchestrator.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Folder yang boleh dibuka seseorang.
 *
 * <p>{@code allFolders} untuk pengelola folder, bukan himpunan berisi semua id:
 * pemakainya tidak perlu menyaring apa pun, dan folder yang baru dibuat sedetik
 * lalu tidak tertinggal di luar himpunan.
 */
public record FolderAccess(boolean allFolders, Set<UUID> folderIds) {

    public static FolderAccess all() {
        return new FolderAccess(true, Set.of());
    }

    public static FolderAccess only(Set<UUID> folderIds) {
        return new FolderAccess(false, Collections.unmodifiableSet(new HashSet<>(folderIds)));
    }

    public boolean allows(UUID folderId) {
        return allFolders || folderIds.contains(folderId);
    }
}
