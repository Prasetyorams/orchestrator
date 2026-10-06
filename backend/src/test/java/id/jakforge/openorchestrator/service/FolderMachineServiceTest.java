package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.repository.AuditRepository;
import id.jakforge.openorchestrator.repository.FolderMachineRepository;
import id.jakforge.openorchestrator.repository.FolderRepository;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.repository.ProcessRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import id.jakforge.openorchestrator.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Siapa yang boleh mendaftarkan mesin ke folder, dan mesin mana. Basis
 * datanya perekam: yang diuji keputusan layanannya dan SQL yang dikirimnya.
 * Alur lengkap melawan PostgreSQL ada di uji ujung-ke-ujung.
 */
class FolderMachineServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID folderId = UUID.randomUUID();
    private final UUID machineId = UUID.randomUUID();
    private final RecordingDatabase database = new RecordingDatabase();
    private final OpenOrchestratorPrincipal principal = new OpenOrchestratorPrincipal(userId, tenantId, "budi", "Automation User");

    /**
     * @param manager    pemanggil punya folders.update
     * @param ownedBy    pemilik folder itu (Folder Saya), atau null untuk folder bersama
     */
    private FolderMachineService service(boolean manager, UUID ownedBy) {
        FolderRepository folders = new FolderRepository(null) {
            @Override
            public Optional<Map<String, Object>> findById(UUID tenant, UUID id) {
                Map<String, Object> folder = new HashMap<>();
                folder.put("id", id.toString());
                folder.put("name", "Produksi");
                if (ownedBy != null) folder.put("ownerId", ownedBy.toString());
                return Optional.of(folder);
            }

            @Override
            public Set<UUID> findAssignedFolderIds(UUID tenant, UUID user) {
                return Set.of(folderId);
            }
        };

        return new FolderMachineService(new FolderMachineRepository(database), new MachineRepository(database),
                new ProcessRepository(database), new FolderAccessService(folders, (p, permission) -> manager),
                new AuditService(new AuditRepository(database), null, null, null), TestProperties.defaults());
    }

    private void machineRow(String state) {
        Map<String, Object> machine = new HashMap<>();
        machine.put("id", machineId.toString());
        machine.put("name", "RPA-PROD-01");
        machine.put("state", state);
        database.answerRow("SELECT id, name, state FROM machines", machine);
    }

    @Test
    @DisplayName("pengelola folder mendaftarkan mesin: satu baris folder_machines, tercatat di audit")
    void managerAddsMachine() {
        machineRow("Active");

        service(true, null).add(principal, folderId.toString(), machineId.toString());

        var insert = database.argumentsOf("INSERT INTO folder_machines");
        assertEquals(List.of(folderId, machineId, tenantId, "budi"), insert);
        assertEquals("Produksi · RPA-PROD-01", database.argumentsOf("INSERT INTO audit_logs").get(4));
        assertTrue(database.argumentsOf("INSERT INTO audit_logs").contains("Tambah mesin"));
    }

    @Test
    @DisplayName("anggota biasa folder bersama tidak boleh mendaftarkan atau mengeluarkan mesin (403)")
    void memberCannotManage() {
        machineRow("Active");
        FolderMachineService anggota = service(false, null);

        for (Runnable aksi : List.<Runnable>of(
                () -> anggota.add(principal, folderId.toString(), machineId.toString()),
                () -> anggota.addAll(principal, folderId.toString(), List.of(machineId.toString())),
                () -> anggota.remove(principal, folderId.toString(), machineId.toString()),
                () -> anggota.available(principal, folderId.toString()))) {
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, aksi::run).status());
        }

        assertTrue(database.statementsContaining("INSERT INTO folder_machines").isEmpty());
        assertTrue(database.statementsContaining("DELETE FROM folder_machines").isEmpty());
    }

    @Test
    @DisplayName("anggota folder boleh MELIHAT mesin folder itu")
    void memberCanList() {
        service(false, null).list(principal, folderId.toString());

        assertFalse(database.statementsContaining("FROM folder_machines fm").isEmpty());
    }

    @Test
    @DisplayName("mesin yang dinonaktifkan tidak bisa didaftarkan (400)")
    void disabledMachineRejected() {
        machineRow("Disabled");

        ApiException error = assertThrows(ApiException.class,
                () -> service(true, null).add(principal, folderId.toString(), machineId.toString()));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertTrue(database.statementsContaining("INSERT INTO folder_machines").isEmpty());
    }

    @Test
    @DisplayName("pemilik Folder Saya hanya boleh mendaftarkan mesin tempat robotnya sendiri bekerja")
    void ownerLimitedToOwnMachines() {
        machineRow("Active");

        // Basis data perekam menjawab "0 baris" untuk pemeriksaan kepemilikan: robotnya bukan miliknya.
        ApiException error = assertThrows(ApiException.class,
                () -> service(false, userId).add(principal, folderId.toString(), machineId.toString()));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
        assertEquals("budi", database.argumentsOf("o.username = ?").getLast());
    }

    @Test
    @DisplayName("daftar mesin yang bisa ditambahkan ke Folder Saya disaring ke mesin robot pemiliknya")
    void ownerAvailableIsFiltered() {
        service(false, userId).available(principal, folderId.toString());

        assertTrue(database.statementContaining("FROM machines m").contains("o.username = ?"));
        assertEquals("budi", database.argumentsOf("FROM machines m").getLast());
    }

    @Test
    @DisplayName("tambah sekaligus: kosong atau lebih dari 100 ditolak; id yang sama dihitung sekali")
    void bulkLimits() {
        machineRow("Active");
        FolderMachineService pengelola = service(true, null);

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> pengelola.addAll(principal, folderId.toString(), List.of())).status());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> pengelola.addAll(principal, folderId.toString(),
                        Collections.nCopies(101, machineId.toString()))).status());

        var hasil = pengelola.addAll(principal, folderId.toString(), List.of(machineId.toString(), machineId.toString()));

        assertEquals(1, hasil.added());
        assertEquals(1, database.statementsContaining("INSERT INTO folder_machines").size());
    }

    @Test
    @DisplayName("mesin atau proses yang tidak ada: 404; tipe runtime tak dikenal: 400")
    void unknownIds() {
        FolderMachineService pengelola = service(true, null);

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> pengelola.add(principal, folderId.toString(), "bukan-uuid")).status());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> pengelola.forProcess(principal, "bukan-uuid", null)).status());

        Map<String, Object> process = new HashMap<>();
        process.put("id", UUID.randomUUID().toString());
        process.put("name", "Tagihan");
        process.put("folderId", folderId.toString());
        database.answerRow("FROM processes", process);

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> pengelola.forProcess(principal, process.get("id").toString(), "Produksi")).status());
    }
}
