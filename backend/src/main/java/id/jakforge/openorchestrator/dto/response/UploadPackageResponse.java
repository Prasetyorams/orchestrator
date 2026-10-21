package id.jakforge.openorchestrator.dto.response;

import java.util.List;

/**
 * Hasil unggah paket manual (POST /api/packages/upload).
 *
 * @param sha256        sidik isi yang DISIMPAN — yang diperiksa robot
 * @param layout        ZIP (disimpan apa adanya), ZIP_IN_FOLDER, atau NUPKG (isinya ditata ulang ke akar)
 * @param converted     isinya ditata ulang supaya workflow ada di akar paket, seperti hasil Terbitkan dari Studio
 * @param processChange CREATED (proses baru di folder bawaan), UPDATED (proses dipindah ke versi ini),
 *                      atau UNCHANGED (proses sudah memakai versi yang lebih baru)
 * @param dependencies  dependensi dari project.json, "Id versi"
 * @param libraries     dependensi yang ada di feed library (V20), "Id versi": dipasang robot sendiri
 * @param warnings      yang tidak bisa dipastikan server, mis. activity yang harus ada di mesin robot
 */
public record UploadPackageResponse(boolean ok, String name, String version, long sizeBytes, String sha256,
                                    String entryPoint, String source, String layout, boolean converted,
                                    String processName, String processChange, List<String> dependencies,
                                    List<String> libraries, List<String> warnings) {
}
