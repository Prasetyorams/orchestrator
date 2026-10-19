package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Membuat atau mengubah kalender hari non-kerja (POST /api/calendars,
 * PUT /api/calendars/{id}).
 *
 * <p>{@code days} adalah SELURUH daftar tanggal kalender itu, bukan
 * tambahannya: yang tidak disebut lagi dihapus. Bentuk tanggal dan batasnya
 * diperiksa layanan.
 *
 * @param days tanggal {@code yyyy-MM-dd} dengan catatan opsional; null berarti kosong
 */
public record SaveCalendarRequest(
        @NotNull(message = "Nama kalender wajib diisi.")
        String name,

        String description,
        List<Day> days) {

    /** @param note mis. "Hari Raya Idulfitri"; hanya untuk dibaca orang */
    public record Day(String date, String note) {
    }

    public SaveCalendarRequest {
        name = Strings.trimToNull(name);
        description = Strings.trimToNull(description);
        days = days == null ? List.of() : days;
    }
}
