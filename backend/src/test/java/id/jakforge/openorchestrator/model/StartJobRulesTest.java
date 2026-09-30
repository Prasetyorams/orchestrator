package id.jakforge.openorchestrator.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tipe runtime dan prioritas job: bentuk baku dari teks bebas. */
class StartJobRulesTest {

    @Test
    @DisplayName("tipe runtime dibakukan tanpa membedakan huruf besar")
    void runtimeTypesAreNormalized() {
        assertEquals(Optional.of("Testing"), RuntimeTypes.parse(" testing "));
        assertEquals(Optional.of("Production"), RuntimeTypes.parse("PRODUCTION"));
        assertEquals(Optional.of("Development"), RuntimeTypes.parse("Development"));
    }

    @Test
    @DisplayName("tipe runtime yang tidak dikenal dan yang kosong tidak menghasilkan apa-apa")
    void unknownRuntimeType() {
        assertEquals(Optional.empty(), RuntimeTypes.parse("Produksi"));
        assertEquals(Optional.empty(), RuntimeTypes.parse(" "));
        assertEquals(Optional.empty(), RuntimeTypes.parse(null));
    }

    @Test
    @DisplayName("urutan katalog dipakai sebagai urutan SQL: Production lebih dulu")
    void runtimeOrderSql() {
        assertEquals("CASE t WHEN 'Production' THEN 0 WHEN 'Testing' THEN 1 WHEN 'Development' THEN 2 ELSE 3 END",
                RuntimeTypes.sqlOrder("t"));
    }

    @Test
    @DisplayName("prioritas dibakukan, termasuk Inherited")
    void prioritiesAreNormalized() {
        assertEquals(Optional.of("High"), JobPriorities.parse("high"));
        assertEquals(Optional.of("Low"), JobPriorities.parse(" LOW "));
        assertEquals(Optional.of("Inherited"), JobPriorities.parse("inherited"));
        assertEquals(Optional.empty(), JobPriorities.parse("Tinggi"));
        assertEquals(Optional.empty(), JobPriorities.parse(""));
    }

    @Test
    @DisplayName("pemicu tidak boleh gagal karena ejaan: yang tidak dikenal dan Inherited menjadi Normal")
    void triggerPriorityFallsBackToNormal() {
        assertEquals("High", JobPriorities.storedOrNormal("HIGH"));
        assertEquals("Normal", JobPriorities.storedOrNormal("Tinggi"));
        assertEquals("Normal", JobPriorities.storedOrNormal("Inherited"));
    }
}
