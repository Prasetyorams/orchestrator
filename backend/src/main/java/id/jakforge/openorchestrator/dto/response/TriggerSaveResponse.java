package id.jakforge.openorchestrator.dto.response;

/** Jawaban simpan pemicu, dengan waktu jalan berikutnya yang sudah dihitung. */
public record TriggerSaveResponse(boolean ok, String nextRunAt) {
}
