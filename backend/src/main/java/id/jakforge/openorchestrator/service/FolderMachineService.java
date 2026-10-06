package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.model.MachineStates;
import id.jakforge.openorchestrator.model.RuntimeTypes;
import id.jakforge.openorchestrator.repository.FolderMachineRepository;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.repository.ProcessRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.security.PermissionChecker;
import id.jakforge.openorchestrator.security.Permissions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pendaftaran mesin ke folder, seperti di UiPath (V12).
 *
 * <p>Sebuah mesin hanya menjalankan job folder tempat ia terdaftar. Yang
 * mengatur pendaftarannya sama dengan yang mengatur robot folder itu:
 * pengelola folder ({@code folders.update}) untuk folder bersama, dan juga
 * pemiliknya untuk Folder Saya — tapi pemilik hanya boleh mendaftarkan mesin
 * tempat robotnya sendiri bekerja, supaya Folder Saya tidak menjadi jalan
 * memakai mesin unattended milik tim.
 *
 * <p>Melihat mesin sebuah folder cukup boleh membuka foldernya: Start Job
 * membutuhkannya.
 */
@Service
@RequiredArgsConstructor
public class FolderMachineService {

    /** Batas satu kali tambah sekaligus. */
    static final int MAX_BULK = 100;

    static final String AUDIT_COMPONENT = "Folder";
    static final String AUDIT_ADD = "Tambah mesin";
    static final String AUDIT_REMOVE = "Keluarkan mesin";

    private final FolderMachineRepository folderMachineRepository;
    private final MachineRepository machineRepository;
    private final ProcessRepository processRepository;
    private final FolderAccessService folderAccessService;
    private final AuditService auditService;
    private final OpenOrchestratorProperties properties;

    /** Hasil tambah sekaligus: yang baru terdaftar, dan yang ternyata sudah terdaftar. */
    public record BulkResult(int added, int skipped) {
    }

    /** Mesin untuk Start Job sebuah proses. */
    public record ProcessMachines(String processId, String processName, String folderId,
                                  List<Map<String, Object>> machines) {
    }

    // -----------------------------------------------------------------
    // Melihat
    // -----------------------------------------------------------------

    /** Mesin yang terdaftar di folder itu, beserta status dan runtime-nya. */
    public List<Map<String, Object>> list(OpenOrchestratorPrincipal principal, String folderIdText) {
        UUID folderId = parseFolderId(folderIdText);
        folderAccessService.requireAccessibleFolder(principal, folderId);

        return withRuntimes(principal.tenantId(), folderMachineRepository.findForFolder(principal.tenantId(), folderId,
                agentOnlineSeconds(), robotOnlineSeconds()));
    }

    /** Mesin yang bisa didaftarkan ke folder itu: belum terdaftar, dan tidak Disabled. */
    public List<Map<String, Object>> available(OpenOrchestratorPrincipal principal, String folderIdText) {
        Management management = requireManagement(principal, folderIdText);

        return withRuntimes(principal.tenantId(), folderMachineRepository.findAvailable(principal.tenantId(),
                management.folderId(), management.ownerOnly() ? principal.username() : null,
                agentOnlineSeconds(), robotOnlineSeconds()));
    }

    // -----------------------------------------------------------------
    // Mendaftarkan dan mengeluarkan
    // -----------------------------------------------------------------

    /** Satu mesin; yang sudah terdaftar dijawab 409 — tidak ada pendaftaran ganda. */
    @Transactional
    public void add(OpenOrchestratorPrincipal principal, String folderIdText, String machineIdText) {
        Management management = requireManagement(principal, folderIdText);
        Map<String, Object> machine = requireUsableMachine(principal, management, machineIdText);
        UUID machineId = Uuids.parseOrNull((String) machine.get("id"));

        if (folderMachineRepository.assign(principal.tenantId(), management.folderId(), machineId,
                principal.username()) == 0) {
            throw ApiException.conflict("Mesin '" + machine.get("name") + "' sudah terdaftar di folder ini.")
                    .withCode("MACHINE_ALREADY_ASSIGNED");
        }

        audit(principal, AUDIT_ADD, management, machine);
    }

    /**
     * Beberapa mesin sekaligus, dalam SATU transaksi: satu mesin yang tidak
     * boleh didaftarkan menggagalkan semuanya, bukan meninggalkan setengahnya.
     * Yang ternyata sudah terdaftar dilewati dan dihitung.
     */
    @Transactional
    public BulkResult addAll(OpenOrchestratorPrincipal principal, String folderIdText, List<String> machineIdTexts) {
        Management management = requireManagement(principal, folderIdText);

        if (machineIdTexts == null || machineIdTexts.isEmpty()) {
            throw ApiException.badRequest("Pilih minimal satu mesin.");
        }

        if (machineIdTexts.size() > MAX_BULK) {
            throw ApiException.badRequest("Paling banyak " + MAX_BULK + " mesin sekaligus.");
        }

        List<Map<String, Object>> machines = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String text : machineIdTexts) {
            Map<String, Object> machine = requireUsableMachine(principal, management, text);
            if (seen.add((String) machine.get("id"))) machines.add(machine);
        }

        int added = 0;

        for (Map<String, Object> machine : machines) {
            UUID machineId = Uuids.parseOrNull((String) machine.get("id"));

            if (folderMachineRepository.assign(principal.tenantId(), management.folderId(), machineId,
                    principal.username()) == 1) {
                added++;
                audit(principal, AUDIT_ADD, management, machine);
            }
        }

        return new BulkResult(added, machines.size() - added);
    }

    /**
     * Keluarkan mesin dari folder. Mesinnya tetap ada; ia hanya tidak lagi
     * menjalankan job folder ini. Job yang sedang berjalan di sana tidak
     * dihentikan.
     */
    @Transactional
    public void remove(OpenOrchestratorPrincipal principal, String folderIdText, String machineIdText) {
        Management management = requireManagement(principal, folderIdText);
        UUID machineId = Uuids.parseOrNull(machineIdText);
        Map<String, Object> machine = machineId == null ? null
                : folderMachineRepository.findMachine(principal.tenantId(), machineId).orElse(null);

        if (machine == null || folderMachineRepository.unassign(principal.tenantId(), management.folderId(), machineId) == 0) {
            throw ApiException.notFound("Mesin itu tidak terdaftar di folder ini.");
        }

        audit(principal, AUDIT_REMOVE, management, machine);
    }

    // -----------------------------------------------------------------
    // Start Job
    // -----------------------------------------------------------------

    /**
     * Mesin yang bisa menjalankan proses itu: yang terdaftar di folder
     * PROSESNYA — bukan semua mesin penyewa. Setiap mesin membawa status dan
     * {@code available}: hanya mesin ONLINE yang bisa dipilih; yang lain
     * tetap dikirim supaya layar bisa menampilkannya tidak aktif beserta
     * alasannya.
     *
     * @param runtimeTypeText bukan kosong: hanya mesin yang punya runtime itu
     */
    public ProcessMachines forProcess(OpenOrchestratorPrincipal principal, String processIdText, String runtimeTypeText) {
        UUID processId = Uuids.parseOrNull(processIdText);
        Map<String, Object> process = processId == null ? null
                : processRepository.findById(principal.tenantId(), processId).orElse(null);

        if (process == null) throw ApiException.notFound("Proses tidak ada.");

        String runtimeType = null;

        if (!RuntimeTypes.isBlank(runtimeTypeText)) {
            runtimeType = RuntimeTypes.parse(runtimeTypeText).orElseThrow(() -> ApiException.badRequest(
                    "Tipe runtime tidak dikenal: '" + runtimeTypeText + "'. Pilih Production, Testing, atau Development."));
        }

        UUID folderId = Uuids.parseOrNull((String) process.get("folderId"));
        folderAccessService.requireAccessibleFolder(principal, folderId);

        List<Map<String, Object>> machines = new ArrayList<>();

        for (Map<String, Object> machine : withRuntimes(principal.tenantId(),
                folderMachineRepository.findForFolder(principal.tenantId(), folderId, agentOnlineSeconds(),
                        robotOnlineSeconds()))) {
            @SuppressWarnings("unchecked")
            Map<String, Integer> runtimes = (Map<String, Integer>) machine.get("runtimes");

            if (runtimeType != null && runtimes.getOrDefault(runtimeType, 0) == 0) continue;

            machine.put("available", MachineStates.ONLINE.equals(machine.get("status")) && !runtimes.isEmpty());
            machines.add(machine);
        }

        return new ProcessMachines(processId.toString(), (String) process.get("name"), folderId.toString(), machines);
    }

    // -----------------------------------------------------------------

    /** Folder yang boleh diatur pemanggil; {@code ownerOnly}: pemilik Folder Saya yang bukan pengelola folder. */
    private record Management(UUID folderId, String folderName, boolean ownerOnly) {
    }

    private Management requireManagement(OpenOrchestratorPrincipal principal, String folderIdText) {
        UUID folderId = parseFolderId(folderIdText);
        Map<String, Object> folder = folderAccessService.requireAccessibleFolder(principal, folderId);
        Object ownerId = folder.get(FolderAccessService.OWNER_ID_COLUMN);

        boolean manager = folderAccessService.canManageFolders(principal);
        boolean owner = ownerId != null && principal.userId().toString().equals(String.valueOf(ownerId));

        if (!manager && !owner) throw PermissionChecker.denied(Permissions.FOLDERS_UPDATE);

        return new Management(folderId, (String) folder.get("name"), !manager);
    }

    /** Mesin penyewa ini, tidak Disabled, dan — untuk pemilik Folder Saya — tempat robotnya sendiri bekerja. */
    private Map<String, Object> requireUsableMachine(OpenOrchestratorPrincipal principal, Management management,
                                                     String machineIdText) {
        UUID machineId = Uuids.parseOrNull(machineIdText);
        Map<String, Object> machine = machineId == null ? null
                : folderMachineRepository.findMachine(principal.tenantId(), machineId).orElse(null);

        if (machine == null) throw ApiException.notFound("Mesin tidak ada.");

        if (MachineStates.DISABLED.equals(machine.get("state"))) {
            throw ApiException.badRequest("Mesin '" + machine.get("name") + "' dinonaktifkan; aktifkan dulu di halaman Mesin.");
        }

        if (management.ownerOnly() && !folderMachineRepository.isUsedBy(principal.tenantId(), machineId,
                principal.username())) {
            throw ApiException.forbidden("Di Folder Saya hanya bisa didaftarkan mesin tempat robot Anda sendiri bekerja.");
        }

        return machine;
    }

    private void audit(OpenOrchestratorPrincipal principal, String action, Management management,
                       Map<String, Object> machine) {
        auditService.record(principal, AUDIT_COMPONENT, action, management.folderName() + " · " + machine.get("name"),
                "folderId=" + management.folderId() + " machineId=" + machine.get("id"));
    }

    private List<Map<String, Object>> withRuntimes(UUID tenantId, List<Map<String, Object>> machines) {
        Map<String, Map<String, Integer>> runtimes = machineRepository.findRuntimesForTenant(tenantId);

        for (Map<String, Object> machine : machines) {
            machine.put("runtimes", new LinkedHashMap<>(runtimes.getOrDefault((String) machine.get("id"), Map.of())));
        }

        return machines;
    }

    private long agentOnlineSeconds() {
        return properties.agent().offlineAfter().toSeconds();
    }

    private long robotOnlineSeconds() {
        return properties.robot().heartbeatTimeout().toSeconds();
    }

    private static UUID parseFolderId(String text) {
        UUID folderId = Uuids.parseOrNull(text);
        if (folderId == null) throw ApiException.notFound(FolderAccessService.FOLDER_NOT_FOUND);
        return folderId;
    }
}
