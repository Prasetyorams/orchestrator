package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Ubah setelan robot (PUT /api/robots/{name}).
 *
 * <p>Medan yang tidak dikirim tidak diubah — KECUALI {@code machineName}: string
 * kosong melepas robot dari mesinnya. Sandi Windows yang kosong berarti "tetap
 * yang lama"; sandi tidak pernah dikirim balik ke dasbor, jadi formulir tidak
 * bisa mengisinya kembali.
 */
public record UpdateRobotRequest(
        String type,
        String environment,
        String description,
        String machineName,
        @Size(max = 200, message = "Akun Windows terlalu panjang.")
        String windowsUsername,
        @Size(max = 256, message = "Sandi Windows terlalu panjang.")
        String windowsPassword,
        Boolean windowsPasswordLocal,
        @Pattern(regexp = "Logoff|KeepLoggedIn", message = "Kebijakan sesi harus Logoff atau KeepLoggedIn.")
        String sessionPolicy) {

    public UpdateRobotRequest {
        type = Strings.emptyToNull(type);
        environment = Strings.emptyToNull(environment);
        windowsPassword = Strings.emptyToNull(windowsPassword);
        sessionPolicy = Strings.emptyToNull(sessionPolicy);
    }
}
