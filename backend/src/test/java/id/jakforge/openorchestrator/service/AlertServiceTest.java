package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.support.RecordingDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Penyaring tingkat dan status baca pada daftar peringatan. */
class AlertServiceTest {

    private final OpenOrchestratorPrincipal principal =
            new OpenOrchestratorPrincipal(UUID.randomUUID(), UUID.randomUUID(), "OO_Admin", "Administrator");
    private final RecordingDatabase database = new RecordingDatabase();
    private final AlertService alertService = new AlertService(new AlertRepository(database));

    @Test
    @DisplayName("peringatan tanpa saringan tingkat menampilkan semua tingkat")
    void withoutSeverityFilterShowsAll() {
        alertService.findAll(principal, null, null, null);

        assertFalse(database.lastStatement().contains("severity IN"), database.lastStatement());
    }

    @Test
    @DisplayName("peringatan bisa disaring satu atau beberapa tingkat, tanpa peduli huruf besar")
    void severityFilterIsCaseInsensitive() {
        alertService.findAll(principal, null, List.of("error,WARNING"), 20);

        List<Object> args = database.lastArguments();

        assertTrue(database.lastStatement().contains("severity IN (?, ?)"), database.lastStatement());
        // Nilainya dikirim dengan ejaan yang tersimpan: Warning, Error.
        assertEquals(List.of("Warning", "Error"), args.subList(2, 4));
        assertEquals(20, args.getLast());
    }

    @Test
    @DisplayName("tingkat peringatan yang tidak dikenal ditolak 400")
    void unknownSeverityIsRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> alertService.findAll(principal, null, List.of("gawat"), null));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
    }

    @Test
    @DisplayName("unread=1 dan unread=true sama-sama berarti hanya yang belum dibaca")
    void unreadAcceptsOneAndTrue() {
        alertService.findAll(principal, "1", null, null);
        assertEquals(true, database.lastArguments().get(1));

        alertService.findAll(principal, "TRUE", null, null);
        assertEquals(true, database.lastArguments().get(1));

        alertService.findAll(principal, "0", null, null);
        assertEquals(false, database.lastArguments().get(1));
    }

    @Test
    @DisplayName("batas baris dijepit: bawaan 50, paling banyak 500")
    void limitIsClamped() {
        alertService.findAll(principal, null, null, null);
        assertEquals(50, database.lastArguments().getLast());

        alertService.findAll(principal, null, null, 99_999);
        assertEquals(500, database.lastArguments().getLast());
    }
}
