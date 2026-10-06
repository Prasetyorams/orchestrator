package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.dto.request.CreateRobotRequest;
import id.jakforge.openorchestrator.dto.request.UpdateRobotRequest;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.LogRepository;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.repository.RobotRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import id.jakforge.openorchestrator.support.TestFolderAccess;
import id.jakforge.openorchestrator.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Resolusi sesi robot unattended (V13) di dasbor: diperiksa sebelum disimpan, dan disimpan apa adanya. */
class RobotResolutionServiceTest {

    private final RecordingDatabase database = new RecordingDatabase();
    private final OpenOrchestratorPrincipal principal =
            new OpenOrchestratorPrincipal(UUID.randomUUID(), UUID.randomUUID(), "admin", "Administrator");

    private RobotService service() {
        return new RobotService(new RobotRepository(database, TestProperties.defaults()), new MachineRepository(database),
                new JobRepository(database), new AlertRepository(database), new LogRepository(database),
                TestFolderAccess.permitAll(), null);
    }

    private static CreateRobotRequest create(Integer width, Integer height, Integer depth) {
        return new CreateRobotRequest("VM-ROBOT-01", null, null, "Unattended", null, null, ".\\robot", null, true,
                "Logoff", width, height, depth);
    }

    private static UpdateRobotRequest update(Integer width, Integer height, Integer depth) {
        return new UpdateRobotRequest(null, null, null, null, null, null, null, null, width, height, depth);
    }

    /** Robot yang sudah tersimpan dengan resolusi itu. */
    private void stored(int width, int height, int depth) {
        Map<String, Object> robot = new HashMap<>();
        robot.put("id", UUID.randomUUID().toString());
        robot.put("name", "VM-ROBOT-01");
        robot.put("type", "Unattended");
        robot.put("sessionPolicy", "Logoff");
        robot.put("windowsPasswordLocal", true);
        robot.put("resolutionWidth", width);
        robot.put("resolutionHeight", height);
        robot.put("resolutionDepth", depth);
        database.answerRow("session_policy, resolution_width, resolution_height, resolution_depth", robot);
    }

    private List<Object> insertedResolution() {
        List<Object> args = database.argumentsOf("INSERT INTO robots");
        return args.subList(args.size() - 3, args.size());
    }

    private List<Object> updatedResolution() {
        List<Object> args = database.argumentsOf("UPDATE robots");
        return args.subList(10, 13);
    }

    @Test
    @DisplayName("robot baru: resolusi disimpan; tanpa resolusi = 0x0 bawaan")
    void createStoresResolution() {
        service().create(principal, create(1920, 1080, 32));
        assertEquals(List.of(1920, 1080, 32), insertedResolution());
    }

    @Test
    @DisplayName("robot baru tanpa resolusi: 0, 0, 0")
    void createDefaults() {
        service().create(principal, create(null, null, null));
        assertEquals(List.of(0, 0, 0), insertedResolution());
    }

    @Test
    @DisplayName("robot baru dengan lebar saja atau kedalaman 12: 400, tidak ada yang disimpan")
    void createRejectsInvalid() {
        for (CreateRobotRequest request : List.of(create(1920, null, null), create(1366, 768, 12), create(100, 100, 0))) {
            ApiException error = assertThrows(ApiException.class, () -> service().create(principal, request));
            assertEquals(HttpStatus.BAD_REQUEST, error.status());
        }

        assertTrue(database.statementsContaining("INSERT INTO robots").isEmpty());
    }

    @Test
    @DisplayName("ubah kedalaman saja: ukuran yang tersimpan dipertahankan")
    void updateKeepsStoredSize() {
        stored(1920, 1080, 32);
        service().update(principal, "VM-ROBOT-01", update(null, null, 24));

        assertEquals(List.of(1920, 1080, 24), updatedResolution());
    }

    @Test
    @DisplayName("ubah lebar saja dari bawaan 0x0: 400 — tinggi tidak boleh tertinggal 0")
    void updateChecksTheMergedPair() {
        stored(0, 0, 0);

        ApiException error = assertThrows(ApiException.class,
                () -> service().update(principal, "VM-ROBOT-01", update(1920, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertTrue(database.statementsContaining("UPDATE robots").isEmpty());
    }

    @Test
    @DisplayName("kembali ke bawaan: 0x0 diterima")
    void updateBackToDefault() {
        stored(1920, 1080, 32);
        service().update(principal, "VM-ROBOT-01", update(0, 0, 0));

        assertEquals(List.of(0, 0, 0), updatedResolution());
    }
}
