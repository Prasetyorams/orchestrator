package id.jakforge.openorchestrator.support;

import id.jakforge.openorchestrator.repository.FolderRepository;
import id.jakforge.openorchestrator.service.FolderAccessService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Aturan akses folder untuk uji layanan yang bukan tentang folder. */
public final class TestFolderAccess {

    private TestFolderAccess() {
    }

    /** Setiap folder ada, dan pemanggilnya pengelola folder: id yang disebut selalu diterima. */
    public static FolderAccessService permitAll() {
        return new FolderAccessService(new EveryFolderExists(), (principal, permission) -> true);
    }

    private static final class EveryFolderExists extends FolderRepository {

        EveryFolderExists() {
            super(null);
        }

        @Override
        public Optional<Map<String, Object>> findById(UUID tenantId, UUID folderId) {
            Map<String, Object> folder = new HashMap<>();
            folder.put("id", folderId.toString());
            return Optional.of(folder);
        }
    }
}
