package id.jakforge.openorchestrator.dto.response;

import java.util.Map;

/**
 * Pekerjaan berikutnya untuk robot; {@code {"job": null}} kalau tidak ada.
 *
 * <p>Selalu berhasil, bahkan ketika tidak ada apa-apa: itulah jawaban yang
 * benar dan yang paling sering.
 */
public record NextJobResponse(Map<String, Object> job) {
}
