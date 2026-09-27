package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.PageLimits;
import id.jakforge.openorchestrator.repository.AuditRepository;
import id.jakforge.openorchestrator.repository.FolderRepository;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.UserRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Jejak audit: ditulis oleh {@code AuditInterceptor}, dibaca di Tenant › Audit.
 *
 * <p>Tidak ada jalan untuk menghapus atau mengubahnya, dengan sengaja: jejak
 * yang bisa dibersihkan oleh orang yang jejaknya tercatat di sana bukan lagi
 * jejak.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    static final int DEFAULT_PAGE_SIZE = 200;
    static final int MAX_PAGE_SIZE = 2000;

    private final AuditRepository auditRepository;
    private final UserRepository userRepository;
    private final FolderRepository folderRepository;
    private final JobRepository jobRepository;

    /**
     * @param component hanya komponen ini (Proses, Aset, ...); kosong berarti semua
     * @param keyword   potongan nama pengguna atau sasaran
     */
    public List<Map<String, Object>> findRecent(OpenOrchestratorPrincipal principal, String component, String keyword,
                                                Integer limit) {
        return auditRepository.findRecent(principal.tenantId(), component, keyword,
                PageLimits.clamp(limit, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE));
    }

    /** Komponen yang pernah tercatat, beserta jumlahnya — untuk pilihan penyaring. */
    public List<Map<String, Object>> countByComponent(OpenOrchestratorPrincipal principal) {
        return auditRepository.countByComponent(principal.tenantId());
    }

    public void record(OpenOrchestratorPrincipal principal, String component, String action, String target, String detail) {
        auditRepository.insert(principal.tenantId(), principal.username(), component, action, target, detail);
    }

    /**
     * Catat masuk. Belum ada token saat masuk, jadi penyewanya dicari dari nama
     * penggunanya; nama yang tidak dikenal tidak dicatat.
     */
    public void recordLogin(String username, String component, String action, String detail) {
        if (username == null) return;

        userRepository.findTenantIdByUsername(username).ifPresent(tenantId ->
                auditRepository.insert(tenantId, username, component, action, null, detail));
    }

    public Optional<String> findFolderName(UUID tenantId, UUID folderId) {
        return folderRepository.findName(tenantId, folderId);
    }

    public Optional<String> findJobProcessName(UUID tenantId, UUID jobId) {
        return jobRepository.findProcessName(tenantId, jobId);
    }
}
