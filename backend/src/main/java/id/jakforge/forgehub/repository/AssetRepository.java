package id.jakforge.forgehub.repository;

import id.jakforge.forgehub.common.Uuids;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Aset — teks, angka, boolean, rahasia, dan kredensial.
 *
 * <p>Nilai rahasia disimpan SUDAH tersandi. Repository ini tidak tahu cara
 * membukanya dan memang tidak perlu tahu: penyandiannya urusan service, dan
 * kunci yang dipegang lapisan data berarti setiap kueri berpotensi membocorkan
 * nilai polos.
 */
@Repository
@RequiredArgsConstructor
public class AssetRepository {

    private static final String CREDENTIAL_TYPE = "Credential";

    private final Database database;

    /**
     * Nilai aset rahasia TIDAK ikut; yang muncul hanya penanda bahwa isinya ada.
     *
     * <p>Nama pengguna aset Credential ikut: itu bukan rahasia, dan tanpa itu
     * daftar kredensial tidak bisa dibedakan satu sama lain.
     *
     * @param folderId null berarti seluruh penyewa
     */
    public List<Map<String, Object>> findAll(UUID tenantId, UUID folderId) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String folderFilter = "";

        if (folderId != null) {
            folderFilter = " AND folder_id = ?";
            args.add(folderId);
        }

        return database.queryRows("""
                SELECT id, name, type, scope, username, description, created_at, updated_at, folder_id,
                       CASE WHEN type IN ('Credential', 'Secret') THEN NULL ELSE value_text END AS value_text,
                       CASE WHEN value_text IS NULL OR value_text = '' THEN FALSE ELSE TRUE END AS has_value
                  FROM assets
                 WHERE tenant_id = ?%s
                 ORDER BY name
                """.formatted(folderFilter), args.toArray());
    }

    /** Folder aset bernama itu, atau kosong kalau asetnya belum ada. */
    public Optional<UUID> findFolderId(UUID tenantId, String name) {
        return database.queryScalar("SELECT folder_id FROM assets WHERE tenant_id = ? AND name = ?", tenantId, name)
                .map(Uuids::fromColumn);
    }

    public int moveToFolder(UUID tenantId, String name, UUID folderId) {
        return database.update("UPDATE assets SET folder_id = ? WHERE tenant_id = ? AND name = ?",
                folderId, tenantId, name);
    }

    /** Tipe, nama pengguna, dan isi tersimpan (tersandi untuk rahasia). */
    public Optional<Map<String, Object>> findValue(UUID tenantId, String name) {
        return database.queryRow("SELECT type, username, value_text FROM assets WHERE tenant_id = ? AND name = ?",
                tenantId, name);
    }

    /** Tipe aset bernama itu, atau kosong kalau belum ada. */
    public Optional<String> findType(UUID tenantId, String name) {
        return database.queryScalar("SELECT type FROM assets WHERE tenant_id = ? AND name = ?", tenantId, name)
                .map(String::valueOf);
    }

    /**
     * @param keepStoredValue biarkan value_text yang lama — untuk rahasia yang
     *                        tidak diisi ulang saat disunting. Lihat
     *                        {@code AssetService.writeAsset}.
     */
    public void update(UUID tenantId, String name, String type, String username, String value,
                       boolean keepStoredValue, String description, String scope) {
        database.update("""
                UPDATE assets
                   SET type = ?, username = ?,
                       value_text = CASE WHEN ? THEN value_text ELSE ? END,
                       description = ?, scope = ?, updated_at = now()
                 WHERE tenant_id = ? AND name = ?
                """, type, username, keepStoredValue, value, description, scope, tenantId, name);
    }

    /** @param folderId null berarti folder bawaan (diisi pemicu basis data). */
    public void insert(UUID tenantId, String name, String type, String username, String value,
                       String description, String scope, UUID folderId) {
        database.update("""
                INSERT INTO assets (id, tenant_id, name, type, username, value_text, description, scope,
                                    folder_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """, UUID.randomUUID(), tenantId, name, type, username, value, description, scope, folderId);
    }

    public int deleteByName(UUID tenantId, String name) {
        return database.update("DELETE FROM assets WHERE tenant_id = ? AND name = ?", tenantId, name);
    }

    // -----------------------------------------------------------------
    // Kredensial — aset bertipe Credential
    //
    // Sejak V3 tidak ada lagi tabel kredensial tersendiri. Yang tersisa di
    // sini adalah pandangan ke aset bertipe Credential, untuk endpoint
    // /api/credentials yang masih dipanggil activity Get Credential.
    // -----------------------------------------------------------------

    /** Kata sandinya (value_text) sengaja tidak ikut dipilih. */
    public List<Map<String, Object>> findCredentials(UUID tenantId) {
        return database.queryRows("""
                SELECT id, name, username, description, created_at
                  FROM assets
                 WHERE tenant_id = ? AND type = ?
                 ORDER BY name
                """, tenantId, CREDENTIAL_TYPE);
    }

    /** Nama pengguna dan kata sandi tersandi ({@code passwordEnc}). */
    public Optional<Map<String, Object>> findCredentialValue(UUID tenantId, String name) {
        return database.queryRow("""
                SELECT username, value_text AS password_enc
                  FROM assets
                 WHERE tenant_id = ? AND name = ? AND type = ?
                """, tenantId, name, CREDENTIAL_TYPE);
    }

    /** Hanya aset bertipe Credential: menghapus lewat jalur kredensial tidak boleh mengenai aset lain. */
    public int deleteCredential(UUID tenantId, String name) {
        return database.update("DELETE FROM assets WHERE tenant_id = ? AND name = ? AND type = ?",
                tenantId, name, CREDENTIAL_TYPE);
    }
}
