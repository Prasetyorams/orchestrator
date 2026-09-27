package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.request.MoveToFolderRequest;
import id.jakforge.forgehub.dto.request.SaveAssetRequest;
import id.jakforge.forgehub.dto.request.SaveCredentialRequest;
import id.jakforge.forgehub.dto.response.CredentialValueResponse;
import id.jakforge.forgehub.dto.response.SaveResponse;
import id.jakforge.forgehub.model.AssetType;
import id.jakforge.forgehub.repository.AssetRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.PermissionChecker;
import id.jakforge.forgehub.security.Permissions;
import id.jakforge.forgehub.security.SecretBox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aturan tentang aset, termasuk kredensial.
 *
 * <p>Penyandian ada DI SINI, bukan di repository. Lapisan data yang memegang
 * kuncinya berarti setiap kueri berpotensi mengembalikan nilai polos, dan
 * satu-satunya yang mencegahnya adalah kedisiplinan penulis kuerinya.
 */
@Service
@RequiredArgsConstructor
public class AssetService {

    private static final String BOOLEAN_TRUE = "true";
    private static final String BOOLEAN_FALSE = "false";

    private final AssetRepository assetRepository;
    private final SecretBox secretBox;
    private final FolderAccessService folderAccessService;
    private final PermissionChecker permissionChecker;

    // -----------------------------------------------------------------
    // Aset
    // -----------------------------------------------------------------

    /** Tanpa {@code folderId}: aset seluruh penyewa. */
    public List<Map<String, Object>> findAll(ForgeHubPrincipal principal, String folderId) {
        return assetRepository.findAll(principal.tenantId(),
                folderAccessService.resolveFolderFilter(principal, folderId));
    }

    /**
     * Buat atau perbarui aset.
     *
     * <p>Aset yang sudah ada di folder lain ditolak, sama seperti proses —
     * nama aset unik per penyewa karena Get Asset mencarinya lewat nama saja.
     * assets.create untuk yang baru, assets.update untuk yang sudah ada.
     */
    @Transactional
    public SaveResponse save(ForgeHubPrincipal principal, SaveAssetRequest request) {
        UUID tenantId = principal.tenantId();
        UUID folderId = folderAccessService.resolveFolderFilter(principal, request.folderId());
        UUID existingFolderId = assetRepository.findFolderId(tenantId, request.name()).orElse(null);

        if (existingFolderId != null && folderId != null && !existingFolderId.equals(folderId)) {
            throw ApiException.conflict("Nama aset '" + request.name() + "' sudah dipakai di folder lain.");
        }

        AssetType type = AssetType.parse(request.type());

        if (type == null) {
            throw ApiException.badRequest("Tipe aset tidak dikenal: '" + request.type() + "'.");
        }

        validateValue(type, request.value());

        String existingType = assetRepository.findType(tenantId, request.name()).orElse(null);
        permissionChecker.requireSave(principal, Permissions.ASSETS, existingType != null);

        // Nama pengguna hanya milik Credential. Aset yang berganti tipe dari
        // Credential ke Text tidak boleh membawa nama pengguna yang tidak
        // lagi ditampilkan di mana pun.
        String username = type == AssetType.Credential ? request.username() : null;

        writeAsset(tenantId, request.name(), type, username, request.value(), request.description(),
                request.scope(), existingType, folderId);

        return SaveResponse.of(existingType == null);
    }

    public void moveToFolder(ForgeHubPrincipal principal, String name, MoveToFolderRequest request) {
        UUID folderId = folderAccessService.resolveFolderFilter(principal, request.folderId());

        if (folderId == null) throw ApiException.badRequest("folderId wajib diisi.");

        if (assetRepository.moveToFolder(principal.tenantId(), name, folderId) == 0) {
            throw ApiException.notFound("Aset '" + name + "' tidak ada.");
        }
    }

    /**
     * Aset rahasia dibuka di sini SAJA.
     *
     * <p>Untuk Credential, {@code value} adalah kata sandinya — sama seperti
     * sebelum kredensial menjadi aset, jadi Get Asset yang sudah membaca aset
     * Credential tetap menerima hal yang sama — dan {@code username} ikut.
     */
    public Map<String, Object> getValue(ForgeHubPrincipal principal, String name) {
        Map<String, Object> row = assetRepository.findValue(principal.tenantId(), name)
                .orElseThrow(() -> ApiException.notFound("Aset '" + name + "' tidak ada."));

        AssetType type = AssetType.parse((String) row.get("type"));
        String storedValue = (String) row.get("valueText");

        // HashMap, bukan Map.of: nilainya boleh null, dan Map.of melempar
        // NullPointerException untuk nilai null. Aset yang ada tapi kosong
        // adalah keadaan yang sah. Bukan record: "username" hanya ada untuk
        // Credential, dan klien membedakan "tidak ada" dari "null".
        Map<String, Object> value = new HashMap<>();
        value.put("name", name);
        value.put("type", row.get("type"));
        value.put("value", type != null && type.isSecret() ? secretBox.unprotect(storedValue) : storedValue);

        if (type == AssetType.Credential) value.put("username", row.get("username"));

        return value;
    }

    public void delete(ForgeHubPrincipal principal, String name) {
        if (assetRepository.deleteByName(principal.tenantId(), name) == 0) {
            throw ApiException.notFound("Aset tidak ada.");
        }
    }

    // -----------------------------------------------------------------
    // Kredensial
    //
    // Sejak V3 kredensial adalah aset bertipe Credential. Jalur di bawah ini
    // tetap ada untuk activity Get Credential dan klien lama; semuanya
    // bekerja pada aset yang sama dengan yang tampil di halaman Aset.
    // -----------------------------------------------------------------

    public List<Map<String, Object>> findCredentials(ForgeHubPrincipal principal) {
        return assetRepository.findCredentials(principal.tenantId());
    }

    @Transactional
    public void saveCredential(ForgeHubPrincipal principal, SaveCredentialRequest request) {
        UUID tenantId = principal.tenantId();
        String existingType = assetRepository.findType(tenantId, request.name()).orElse(null);

        permissionChecker.requireSave(principal, Permissions.ASSETS, existingType != null);

        // Dulu kredensial dan aset punya daftar nama sendiri-sendiri; sekarang
        // satu. Menyimpan kredensial di atas aset Text bernama sama akan
        // menimpa nilai yang dipakai proses lain tanpa ada yang memintanya.
        if (existingType != null && AssetType.parse(existingType) != AssetType.Credential) {
            throw ApiException.conflict(
                    "Nama '" + request.name() + "' sudah dipakai aset bertipe " + existingType + ".");
        }

        writeAsset(tenantId, request.name(), AssetType.Credential, request.username(), request.password(),
                request.description(), SaveAssetRequest.DEFAULT_SCOPE, existingType, null);
    }

    public CredentialValueResponse getCredentialValue(ForgeHubPrincipal principal, String name) {
        Map<String, Object> row = assetRepository.findCredentialValue(principal.tenantId(), name)
                .orElseThrow(() -> ApiException.notFound("Kredensial tidak ada."));

        return new CredentialValueResponse(
                (String) row.get("username"),
                secretBox.unprotect((String) row.get("passwordEnc")));
    }

    public void deleteCredential(ForgeHubPrincipal principal, String name) {
        if (assetRepository.deleteCredential(principal.tenantId(), name) == 0) {
            throw ApiException.notFound("Kredensial tidak ada.");
        }
    }

    // -----------------------------------------------------------------
    // Alat
    // -----------------------------------------------------------------

    /**
     * Buat atau perbarui satu aset.
     *
     * <p>Rahasia yang DIKOSONGKAN saat menyunting berarti "biarkan yang lama",
     * bukan "hapus". Layar tidak pernah menerima isi rahasia, jadi isian kata
     * sandi di dialog sunting selalu mulai kosong; memperlakukannya sebagai
     * penghapusan berarti setiap orang yang hanya membetulkan keterangan
     * diam-diam menghapus kata sandinya — dan yang tahu pertama kali adalah
     * robot yang gagal masuk.
     *
     * <p>Hanya kalau tipenya TETAP. Aset yang berganti tipe menerima nilai
     * yang baru apa adanya: teks polos yang tiba-tiba dianggap tersandi tidak
     * akan pernah bisa dibuka.
     */
    private void writeAsset(UUID tenantId, String name, AssetType type, String username, String value,
                            String description, String scope, String existingType, UUID folderId) {

        String storedValue = type.isSecret() ? secretBox.protect(value) : value;

        if (existingType == null) {
            assetRepository.insert(tenantId, name, type.name(), username, storedValue, description, scope, folderId);
            return;
        }

        boolean keepStoredValue = type.isSecret()
                && (value == null || value.isEmpty())
                && type == AssetType.parse(existingType);

        assetRepository.update(tenantId, name, type.name(), username, storedValue, keepStoredValue,
                description, scope);
    }

    /**
     * Nilai bertipe angka dan boolean diperiksa DI SINI, bukan dibiarkan
     * meledak nanti di dalam robot yang sedang berjalan. Kegagalan di sini
     * dilihat orang yang baru saja mengetiknya; kegagalan di sana dilihat
     * sebagai automasi yang berhenti di tengah malam.
     */
    private static void validateValue(AssetType type, String value) {
        if (value == null || value.isEmpty()) return;

        if (type == AssetType.Integer) {
            try {
                Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("Aset bertipe Integer harus berisi bilangan bulat.");
            }
        }

        if (type == AssetType.Bool) {
            String trimmed = value.trim();

            if (!trimmed.equalsIgnoreCase(BOOLEAN_TRUE) && !trimmed.equalsIgnoreCase(BOOLEAN_FALSE)) {
                throw ApiException.badRequest("Aset bertipe Bool harus berisi true atau false.");
            }
        }
    }
}
