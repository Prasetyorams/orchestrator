package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import id.jakforge.openorchestrator.model.RetryPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Membuat atau memperbarui proses di sebuah folder (POST /api/processes).
 *
 * <p>Medan yang tidak dikirim tidak menghapus nilai yang sudah ada.
 *
 * @param folderId         folder prosesnya; kosong berarti proses bernama itu di mana
 *                         pun ia berada, atau folder bawaan untuk yang baru
 * @param timeoutSeconds   batas waktu job robot unattended; 0 = tanpa batas
 * @param stopGraceSeconds jeda berhenti rapi sebelum Executor dimatikan paksa
 * @param maxRetries       percobaan ulang otomatis untuk kegagalan sebelum workflow berjalan
 * @param priority         prioritas bawaan job proses ini (Low, Normal, High) — dipakai job yang
 *                         dijalankan dengan prioritas "Inherited"
 */
public record SaveProcessRequest(
        @NotNull(message = "Nama proses wajib diisi.")
        String name,
        String packageName,
        String packageVersion,
        String environment,
        String description,
        String folderId,
        @Min(value = 0, message = "Batas waktu tidak boleh negatif.")
        @Max(value = 604800, message = "Batas waktu paling lama 7 hari.")
        Integer timeoutSeconds,
        @Min(value = 5, message = "Jeda berhenti minimal 5 detik.")
        @Max(value = 600, message = "Jeda berhenti paling lama 600 detik.")
        Integer stopGraceSeconds,
        @Min(value = 0, message = "Percobaan ulang tidak boleh negatif.")
        @Max(value = RetryPolicy.MAX_RETRIES_LIMIT, message = "Percobaan ulang paling banyak 2 kali.")
        Integer maxRetries,
        String priority) {

    public SaveProcessRequest {
        name = Strings.trimToNull(name);
        packageName = Strings.emptyToNull(packageName);
        packageVersion = Strings.emptyToNull(packageVersion);
        environment = Strings.emptyToNull(environment);
        description = Strings.emptyToNull(description);
        folderId = Strings.emptyToNull(folderId);
        priority = Strings.emptyToNull(priority);
    }

    /** Bentuk lama tanpa setelan robot unattended. */
    public SaveProcessRequest(String name, String packageName, String packageVersion, String environment,
                              String description, String folderId) {
        this(name, packageName, packageVersion, environment, description, folderId, null, null, null, null);
    }

    public boolean hasRunSettings() {
        return timeoutSeconds != null || stopGraceSeconds != null || maxRetries != null || priority != null;
    }
}
