package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Robot baru yang didaftarkan dari dasbor (POST /api/robots).
 *
 * <p>Robot unattended yang dilayani Robot Agent diikat ke mesin lewat
 * {@code machineName}, dan membawa akun Windows-nya. Sandi Windows hanya bisa
 * DITULIS: tidak ada endpoint yang mengembalikannya ke dasbor.
 *
 * @param windowsUsername      {@code DOMAIN&#92;user}, atau {@code .&#92;user} untuk akun lokal
 * @param windowsPasswordLocal sandinya disimpan di mesin robot, bukan di Orchestrator
 * @param sessionPolicy        sesudah job: {@code Logoff} (bawaan) atau {@code KeepLoggedIn}
 * @param resolutionWidth      resolusi sesi unattended (V13); null atau 0 = bawaan agent. Lihat
 *                             {@link id.jakforge.openorchestrator.model.RobotResolution}
 */
public record CreateRobotRequest(
        @NotNull(message = "Nama robot wajib diisi.")
        String name,
        String machineName,
        String username,
        String type,
        String environment,
        String description,
        @Size(max = 200, message = "Akun Windows terlalu panjang.")
        String windowsUsername,
        @Size(max = 256, message = "Sandi Windows terlalu panjang.")
        String windowsPassword,
        Boolean windowsPasswordLocal,
        @Pattern(regexp = "Logoff|KeepLoggedIn", message = "Kebijakan sesi harus Logoff atau KeepLoggedIn.")
        String sessionPolicy,
        Integer resolutionWidth,
        Integer resolutionHeight,
        Integer resolutionDepth) {

    static final String DEFAULT_TYPE = "Unattended";
    static final String DEFAULT_ENVIRONMENT = "Production";
    public static final String DEFAULT_SESSION_POLICY = "Logoff";

    public CreateRobotRequest {
        name = Strings.trimToNull(name);
        machineName = Strings.emptyToNull(machineName);
        username = Strings.emptyToNull(username);
        type = Strings.defaultIfEmpty(type, DEFAULT_TYPE);
        environment = Strings.defaultIfEmpty(environment, DEFAULT_ENVIRONMENT);
        description = Strings.emptyToNull(description);
        windowsUsername = Strings.trimToNull(windowsUsername);
        windowsPassword = Strings.emptyToNull(windowsPassword);
        sessionPolicy = Strings.defaultIfEmpty(sessionPolicy, DEFAULT_SESSION_POLICY);
    }

    /** Bentuk lama tanpa setelan unattended. */
    public CreateRobotRequest(String name, String machineName, String username, String type, String environment,
                              String description) {
        this(name, machineName, username, type, environment, description, null, null, null, null, null, null, null);
    }
}
