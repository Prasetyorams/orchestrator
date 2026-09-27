package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Cron;
import id.jakforge.openorchestrator.dto.request.SaveTriggerRequest;
import id.jakforge.openorchestrator.dto.response.TriggerSaveResponse;
import id.jakforge.openorchestrator.dto.response.TriggerToggleResponse;
import id.jakforge.openorchestrator.repository.TriggerRepository;
import id.jakforge.openorchestrator.repository.TriggerRepository.TriggerSchedule;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.PermissionChecker;
import id.jakforge.openorchestrator.security.Permissions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aturan tentang pemicu terjadwal: membuat, mengubah, menyalakan, menghapus.
 *
 * <p>Pemicu tinggal di folder PROSESNYA, dan namanya unik di folder itu.
 * Menjalankan pemicu yang jatuh tempo ada di {@link TriggerFiringService}.
 */
@Service
@RequiredArgsConstructor
public class TriggerService {

    private static final String TRIGGER_NOT_FOUND = "Pemicu tidak ada.";

    private final TriggerRepository triggerRepository;
    private final ProcessService processService;
    private final NextRunCalculator nextRunCalculator;
    private final FolderAccessService folderAccessService;
    private final PermissionChecker permissionChecker;

    /**
     * Tanpa {@code folderId}: pemicu seluruh penyewa.
     *
     * <p>Menyimpan membawa {@code folderId} di badan, mengalihkan dan menghapus
     * membawa {@code ?folderId=}. Tanpa itu, yang dimaksud adalah pemicu
     * bernama itu di mana pun ia berada (lihat {@link FolderLocations#resolve}).
     */
    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal, String folderId) {
        return triggerRepository.findAll(principal.tenantId(),
                folderAccessService.resolveFolderFilter(principal, folderId));
    }

    /**
     * Simpan pemicu di folder prosesnya.
     *
     * <p>triggers.create untuk yang baru, triggers.update untuk yang sudah ada.
     */
    @Transactional
    public TriggerSaveResponse save(OpenOrchestratorPrincipal principal, SaveTriggerRequest request) {
        UUID tenantId = principal.tenantId();
        UUID requestedFolder = folderAccessService.resolveFolderFilter(principal, request.folderId());

        // Cron DIVALIDASI di sini, bukan dibiarkan sampai penjadwal. Ekspresi
        // yang salah baru ketahuan pada putaran penjadwal berikutnya, dan orang
        // yang menekan "Buat pemicu" sudah pergi.
        if (request.usesCron() && !Cron.isValid(request.cron())) {
            throw ApiException.badRequest("Ekspresi cron tidak sah: " + request.cron()
                    + ". Bentuknya lima ruas: menit jam tanggal bulan hari, "
                    + "mis. \"0 7 * * 1-5\" untuk tiap hari kerja pukul 07:00.");
        }

        if (!request.usesCron() && request.intervalMinutes() < NextRunCalculator.MIN_INTERVAL_MINUTES) {
            throw ApiException.badRequest("Selang waktu minimal 1 menit.");
        }

        // Nama zona diperiksa juga. Penjadwal memang jatuh ke UTC untuk nama
        // yang tidak dikenal, tapi jatuh diam-diam berarti pemicunya berjalan
        // tujuh jam meleset tanpa ada yang tahu sebabnya.
        if (!SaveTriggerRequest.DEFAULT_TIMEZONE.equals(request.timezone()) && !isKnownZone(request.timezone())) {
            throw ApiException.badRequest("Zona waktu tidak dikenal: '" + request.timezone()
                    + "'. Pakai nama IANA, mis. \"Asia/Jakarta\".");
        }

        UUID folder = processService.resolveProcessFolder(tenantId, request.processName(), requestedFolder);

        if (folder == null) {
            throw ApiException.badRequest(requestedFolder == null
                    ? "Proses '" + request.processName() + "' belum diterbitkan ke OpenOrchestrator."
                    : "Proses '" + request.processName() + "' tidak ada di folder ini.");
        }

        OffsetDateTime nextRunAt = nextRunCalculator.nextRun(request.cron(), request.intervalMinutes(),
                Cron.zoneOrUtc(request.timezone()));

        boolean alreadyExists = triggerRepository.existsInFolder(tenantId, folder, request.name());
        permissionChecker.requireSave(principal, Permissions.TRIGGERS, alreadyExists);

        if (alreadyExists) {
            triggerRepository.update(tenantId, folder, request.toDefinition(), nextRunAt);
        } else {
            triggerRepository.insert(tenantId, folder, request.toDefinition(), nextRunAt);
        }

        return new TriggerSaveResponse(true, String.valueOf(nextRunAt));
    }

    /**
     * Nyalakan atau matikan.
     *
     * <p>Waktu jalan berikutnya dihitung ULANG saat dinyalakan, bukan dipakai
     * yang tersimpan. Pemicu yang dimatikan seminggu lalu menyimpan waktu yang
     * sudah lewat, dan menyalakannya kembali akan membuatnya langsung berjalan
     * — biasanya bukan itu yang dimaksud orang yang menekan tombolnya.
     */
    @Transactional
    public TriggerToggleResponse toggle(OpenOrchestratorPrincipal principal, String name, String folderId) {
        UUID tenantId = principal.tenantId();
        UUID folder = resolveTriggerFolder(tenantId, name, folderAccessService.resolveFolderFilter(principal, folderId));

        TriggerSchedule schedule = triggerRepository.findSchedule(tenantId, folder, name)
                .orElseThrow(() -> ApiException.notFound(TRIGGER_NOT_FOUND));

        boolean enable = !schedule.enabled();

        OffsetDateTime nextRunAt = enable
                ? nextRunCalculator.nextRun(schedule.cron(), schedule.intervalMinutes(),
                        Cron.zoneOrUtc(schedule.timezone()))
                : null;

        triggerRepository.setEnabled(tenantId, folder, name, enable, nextRunAt);

        return new TriggerToggleResponse(true, enable);
    }

    public void delete(OpenOrchestratorPrincipal principal, String name, String folderId) {
        UUID tenantId = principal.tenantId();
        UUID folder = resolveTriggerFolder(tenantId, name, folderAccessService.resolveFolderFilter(principal, folderId));

        if (triggerRepository.delete(tenantId, folder, name) == 0) {
            throw ApiException.notFound(TRIGGER_NOT_FOUND);
        }
    }

    /** Folder pemicu yang dimaksud; aturannya sama dengan proses ({@link FolderLocations#resolve}). */
    private UUID resolveTriggerFolder(UUID tenantId, String name, UUID folderId) {
        UUID folder = FolderLocations.resolve(triggerRepository.findLocations(tenantId, name), folderId,
                "Pemicu '" + name + "' ada di beberapa folder. Sebutkan foldernya.");

        if (folder == null) throw ApiException.notFound(TRIGGER_NOT_FOUND);

        return folder;
    }

    private static boolean isKnownZone(String zoneName) {
        if (zoneName == null || zoneName.isBlank()) return false;

        try {
            ZoneId.of(zoneName.trim());
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
