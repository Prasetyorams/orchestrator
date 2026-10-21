package id.jakforge.openorchestrator.dto.response;

import java.util.List;
import java.util.Map;

/**
 * Hasil unggah library (POST /api/libraries, V20).
 *
 * @param id           Id library dari .nuspec
 * @param sha256       sidik .nupkg yang disimpan — yang dicocokkan robot sebelum memakai DLL-nya
 * @param assemblyFile jalur DLL di dalam .nupkg, mis. lib/net45/Tagihan.Umum.dll
 * @param dependencies dependensi dari .nuspec, Id → versi
 * @param warnings     yang tidak menghalangi, mis. dependensi yang belum ada di feed
 */
public record UploadLibraryResponse(boolean ok, String id, String version, String description, long sizeBytes,
                                    String sha256, String assemblyFile, Map<String, String> dependencies,
                                    List<String> warnings) {
}
