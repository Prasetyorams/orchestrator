package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.request.SaveAssetRequest;
import id.jakforge.forgehub.dto.request.SaveCredentialRequest;
import id.jakforge.forgehub.dto.response.CredentialValueResponse;
import id.jakforge.forgehub.repository.AssetRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.SecretBox;
import id.jakforge.forgehub.support.TestFolderAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kredensial sebagai aset bertipe Credential.
 *
 * <p>Penyandiannya ASLI — SecretBox dengan kunci sementara — supaya yang diuji
 * juga bahwa kata sandi benar-benar tersimpan tersandi dan bisa dibuka lagi
 * lewat jalur Get Credential. Yang palsu hanya tabelnya.
 */
class AssetServiceTest {

    private final ForgeHubPrincipal principal =
            new ForgeHubPrincipal(UUID.randomUUID(), UUID.randomUUID(), "FH_Admin", "Administrator");
    private final FakeAssetRepository assets = new FakeAssetRepository();

    private AssetService assetService;

    @BeforeEach
    void createService(@TempDir Path keyDirectory) throws Exception {
        assetService = new AssetService(assets, new SecretBox(keyDirectory.resolve("signing.key").toString()),
                TestFolderAccess.permitAll(), (caller, permission) -> true);
    }

    @Test
    @DisplayName("aset Credential menyimpan nama pengguna, dan kata sandinya tersandi")
    void credentialIsStoredAsAsset() {
        save("Email", "Credential", "budi", "rahasia-123");

        Map<String, Object> row = assets.rows.get("Email");

        assertEquals("Credential", row.get("type"));
        assertEquals("budi", row.get("username"));
        assertNotEquals("rahasia-123", row.get("valueText"));
    }

    @Test
    @DisplayName("Get Credential tetap membaca nama pengguna dan kata sandi dari aset itu")
    void getCredentialReadsTheAsset() {
        save("Email", "Credential", "budi", "rahasia-123");

        CredentialValueResponse credential = assetService.getCredentialValue(principal, "Email");

        assertEquals("budi", credential.username());
        assertEquals("rahasia-123", credential.password());
    }

    @Test
    @DisplayName("Get Asset pada aset Credential menerima kata sandinya sebagai value, plus username")
    void getAssetReturnsPasswordAsValue() {
        save("Email", "Credential", "budi", "rahasia-123");

        Map<String, Object> value = assetService.getValue(principal, "Email");

        assertEquals("rahasia-123", value.get("value"));
        assertEquals("budi", value.get("username"));
    }

    @Test
    @DisplayName("menyunting tanpa mengisi ulang kata sandi TIDAK menghapusnya")
    void emptyPasswordKeepsStoredValue() {
        save("Email", "Credential", "budi", "rahasia-123");
        save("Email", "Credential", "budi.s", null);

        CredentialValueResponse credential = assetService.getCredentialValue(principal, "Email");

        assertEquals("budi.s", credential.username());
        assertEquals("rahasia-123", credential.password());
    }

    @Test
    @DisplayName("Secret yang disunting tanpa isi juga dipertahankan")
    void emptySecretKeepsStoredValue() {
        save("KunciApi", "Secret", null, "abc");
        save("KunciApi", "Secret", null, null);

        assertEquals("abc", assetService.getValue(principal, "KunciApi").get("value"));
    }

    @Test
    @DisplayName("berganti tipe dengan isi kosong mengosongkan nilainya, tidak menyeret nilai tersandi")
    void typeChangeDropsEncryptedValue() {
        save("Alamat", "Secret", null, "abc");
        save("Alamat", "Text", null, null);

        assertNull(assets.rows.get("Alamat").get("valueText"));
        assertNull(assetService.getValue(principal, "Alamat").get("value"));
    }

    @Test
    @DisplayName("nama pengguna hanya disimpan untuk Credential")
    void usernameOnlyForCredential() {
        save("Folder", "Text", "tidak-dipakai", "C:\\Data");

        assertNull(assets.rows.get("Folder").get("username"));
        assertFalse(assetService.getValue(principal, "Folder").containsKey("username"));
    }

    @Test
    @DisplayName("POST /api/credentials lama menghasilkan aset Credential yang sama")
    void legacyCredentialPathCreatesCredentialAsset() {
        assetService.saveCredential(principal, new SaveCredentialRequest("SAP", "admin", "p@ss", null));

        assertEquals("Credential", assets.rows.get("SAP").get("type"));
        assertEquals("p@ss", assetService.getValue(principal, "SAP").get("value"));
        assertEquals(List.of("SAP"),
                assetService.findCredentials(principal).stream().map(credential -> credential.get("name")).toList());
    }

    @Test
    @DisplayName("jalur kredensial lama tidak boleh menimpa aset lain yang bernama sama")
    void legacyCredentialPathDoesNotOverwriteOtherAsset() {
        save("SAP", "Text", null, "https://sap.local");

        ApiException error = assertThrows(ApiException.class, () -> assetService.saveCredential(principal,
                new SaveCredentialRequest("SAP", "admin", "x", null)));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("Text", assets.rows.get("SAP").get("type"));
        assertEquals("https://sap.local", assets.rows.get("SAP").get("valueText"));
    }

    @Test
    @DisplayName("Get Credential untuk aset yang bukan Credential dijawab 404")
    void getCredentialOfNonCredentialIsNotFound() {
        save("SAP", "Text", null, "https://sap.local");

        ApiException error = assertThrows(ApiException.class,
                () -> assetService.getCredentialValue(principal, "SAP"));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
    }

    @Test
    @DisplayName("menghapus lewat jalur kredensial tidak mengenai aset lain")
    void deleteThroughCredentialPathLeavesOtherAssets() {
        save("SAP", "Text", null, "https://sap.local");

        assertThrows(ApiException.class, () -> assetService.deleteCredential(principal, "SAP"));
        assertTrue(assets.rows.containsKey("SAP"));
    }

    @Test
    @DisplayName("tipe aset yang tidak dikenal ditolak 400")
    void unknownTypeIsRejected() {
        ApiException error = assertThrows(ApiException.class, () -> save("Angka", "Desimal", null, "1.5"));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertEquals("Tipe aset tidak dikenal: 'Desimal'.", error.getMessage());
    }

    @Test
    @DisplayName("nilai Integer dan Bool diperiksa sebelum disimpan")
    void typedValuesAreValidated() {
        assertEquals("Aset bertipe Integer harus berisi bilangan bulat.",
                assertThrows(ApiException.class, () -> save("Batas", "Integer", null, "sepuluh")).getMessage());
        assertEquals("Aset bertipe Bool harus berisi true atau false.",
                assertThrows(ApiException.class, () -> save("Aktif", "Bool", null, "ya")).getMessage());

        save("Batas", "Integer", null, " 10 ");
        save("Aktif", "Bool", null, "TRUE");
    }

    // ---------- folder ----------

    @Test
    @DisplayName("aset baru tinggal di folder tempat ia dibuat")
    void newAssetLivesInItsFolder() {
        UUID folder = UUID.randomUUID();

        saveIn(folder, "Alamat", "Text", null, "https://contoh.id");

        assertEquals(folder, assets.rows.get("Alamat").get("folderId"));
    }

    @Test
    @DisplayName("nama yang sudah dipakai di folder lain ditolak 409, asetnya tidak berubah")
    void nameUsedInOtherFolderIsConflict() {
        saveIn(UUID.randomUUID(), "Alamat", "Text", null, "lama");

        ApiException error = assertThrows(ApiException.class,
                () -> saveIn(UUID.randomUUID(), "Alamat", "Text", null, "baru"));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("lama", assets.rows.get("Alamat").get("valueText"));
    }

    @Test
    @DisplayName("menyunting dari folder yang sama, atau tanpa folder seperti Studio, tetap memperbarui")
    void editingFromSameFolderOrWithoutFolderUpdates() {
        UUID folder = UUID.randomUUID();

        saveIn(folder, "Alamat", "Text", null, "lama");
        saveIn(folder, "Alamat", "Text", null, "baru");
        assertEquals("baru", assets.rows.get("Alamat").get("valueText"));

        save("Alamat", "Text", null, "dari-studio");
        assertEquals("dari-studio", assets.rows.get("Alamat").get("valueText"));
        assertEquals(folder, assets.rows.get("Alamat").get("folderId"));
    }

    // ---------- alat ----------

    private void save(String name, String type, String username, String value) {
        saveIn(null, name, type, username, value);
    }

    private void saveIn(UUID folder, String name, String type, String username, String value) {
        assetService.save(principal, new SaveAssetRequest(name, type, username, value, null, null,
                folder == null ? null : folder.toString()));
    }

    /**
     * Tabel assets di memori, meniru kueri AssetRepository satu per satu —
     * termasuk syarat {@code type = 'Credential'} pada jalur kredensial.
     */
    private static final class FakeAssetRepository extends AssetRepository {

        static final UUID DEFAULT_FOLDER = UUID.randomUUID();

        final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();

        FakeAssetRepository() {
            super(null);
        }

        @Override
        public Optional<String> findType(UUID tenantId, String name) {
            return rows.containsKey(name) ? Optional.of((String) rows.get(name).get("type")) : Optional.empty();
        }

        @Override
        public Optional<Map<String, Object>> findValue(UUID tenantId, String name) {
            return rows.containsKey(name) ? Optional.of(new HashMap<>(rows.get(name))) : Optional.empty();
        }

        @Override
        public Optional<UUID> findFolderId(UUID tenantId, String name) {
            return rows.containsKey(name) ? Optional.of((UUID) rows.get(name).get("folderId")) : Optional.empty();
        }

        @Override
        public void insert(UUID tenantId, String name, String type, String username, String value,
                           String description, String scope, UUID folderId) {
            Map<String, Object> row = new HashMap<>();
            row.put("name", name);
            row.put("type", type);
            row.put("username", username);
            row.put("valueText", value);
            row.put("description", description);
            // Pemicu basis data mengisi folder bawaan; di sini cukup sebuah id.
            row.put("folderId", folderId != null ? folderId : DEFAULT_FOLDER);
            rows.put(name, row);
        }

        @Override
        public void update(UUID tenantId, String name, String type, String username, String value,
                           boolean keepStoredValue, String description, String scope) {
            Map<String, Object> row = rows.get(name);
            row.put("type", type);
            row.put("username", username);
            if (!keepStoredValue) row.put("valueText", value);
            row.put("description", description);
        }

        @Override
        public List<Map<String, Object>> findCredentials(UUID tenantId) {
            return rows.values().stream().filter(row -> "Credential".equals(row.get("type"))).toList();
        }

        @Override
        public Optional<Map<String, Object>> findCredentialValue(UUID tenantId, String name) {
            Map<String, Object> row = rows.get(name);
            if (row == null || !"Credential".equals(row.get("type"))) return Optional.empty();

            Map<String, Object> credential = new HashMap<>();
            credential.put("username", row.get("username"));
            credential.put("passwordEnc", row.get("valueText"));
            return Optional.of(credential);
        }

        @Override
        public int deleteCredential(UUID tenantId, String name) {
            Map<String, Object> row = rows.get(name);
            if (row == null || !"Credential".equals(row.get("type"))) return 0;

            rows.remove(name);
            return 1;
        }
    }
}
