package id.jakforge.openorchestrator.dto.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Execute the process N times": hanya bilangan bulat yang diterima, tanpa pembulatan diam-diam. */
class CreateJobRequestTest {

    @Test
    @DisplayName("bilangan bulat diterima, sebagai angka maupun teks")
    void integersAreAccepted() {
        assertEquals(3, CreateJobRequest.strictInteger(3));
        assertEquals(3, CreateJobRequest.strictInteger("3"));
        assertEquals(2, CreateJobRequest.strictInteger(2.0));
        assertEquals(-1, CreateJobRequest.strictInteger(-1), "tanda negatif ditolak layanan, bukan di sini");
    }

    @Test
    @DisplayName("pecahan dan teks bukan angka tidak dibulatkan")
    void nonIntegersAreRejected() {
        assertNull(CreateJobRequest.strictInteger(1.5));
        assertNull(CreateJobRequest.strictInteger("1.5"));
        assertNull(CreateJobRequest.strictInteger("tiga"));
        assertNull(CreateJobRequest.strictInteger(""));
        assertNull(CreateJobRequest.strictInteger(1e20));
    }

    @Test
    @DisplayName("count yang dikirim tapi tidak terbaca ditandai, bukan diganti 1")
    void malformedCountIsFlagged() {
        Map<String, Object> body = new HashMap<>(Map.of("processName", "Tagihan", "count", "1.5"));

        CreateJobRequest request = CreateJobRequest.fromBody(body);

        assertNull(request.count());
        assertTrue(request.countMalformed());
    }

    @Test
    @DisplayName("tanpa count, tanpa prioritas: satu kali, prioritas diserahkan ke layanan (Inherited)")
    void defaults() {
        CreateJobRequest request = CreateJobRequest.fromBody(Map.of("processName", "Tagihan"));

        assertNull(request.count());
        assertFalse(request.countMalformed());
        assertNull(request.priority());
        assertEquals("Manual", request.source());
    }
}
