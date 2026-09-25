package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.repository.VaultRepository;
import id.jakforge.forgehub.security.SecretBox;
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
class VaultServiceTest {

    private final UUID penyewa = UUID.randomUUID();
    private final AsetPalsu tabel = new AsetPalsu();

    private VaultService layanan;

    @BeforeEach
    void siapkan(@TempDir Path folder) throws Exception {
        layanan = new VaultService(tabel, new SecretBox(folder.resolve("signing.key").toString()));
    }

    @Test
    @DisplayName("aset Credential menyimpan nama pengguna, dan kata sandinya tersandi")
    void kredensialSebagaiAset() {
        simpan("Email", "Credential", "budi", "rahasia-123");

        Map<String, Object> baris = tabel.baris.get("Email");

        assertEquals("Credential", baris.get("type"));
        assertEquals("budi", baris.get("username"));
        assertNotEquals("rahasia-123", baris.get("valueText"));
    }

    @Test
    @DisplayName("Get Credential tetap membaca nama pengguna dan kata sandi dari aset itu")
    void jalurGetCredential() {
        simpan("Email", "Credential", "budi", "rahasia-123");

        Map<String, Object> nilai = layanan.nilaiKredensial(penyewa, "Email");

        assertEquals("budi", nilai.get("username"));
        assertEquals("rahasia-123", nilai.get("password"));
    }

    @Test
    @DisplayName("Get Asset pada aset Credential menerima kata sandinya sebagai value, plus username")
    void jalurGetAsset() {
        simpan("Email", "Credential", "budi", "rahasia-123");

        Map<String, Object> nilai = layanan.nilaiAset(penyewa, "Email");

        assertEquals("rahasia-123", nilai.get("value"));
        assertEquals("budi", nilai.get("username"));
    }

    @Test
    @DisplayName("menyunting tanpa mengisi ulang kata sandi TIDAK menghapusnya")
    void sandiKosongDipertahankan() {
        simpan("Email", "Credential", "budi", "rahasia-123");
        simpan("Email", "Credential", "budi.s", null);

        Map<String, Object> nilai = layanan.nilaiKredensial(penyewa, "Email");

        assertEquals("budi.s", nilai.get("username"));
        assertEquals("rahasia-123", nilai.get("password"));
    }

    @Test
    @DisplayName("Secret yang disunting tanpa isi juga dipertahankan")
    void secretKosongDipertahankan() {
        simpan("KunciApi", "Secret", null, "abc");
        simpan("KunciApi", "Secret", null, null);

        assertEquals("abc", layanan.nilaiAset(penyewa, "KunciApi").get("value"));
    }

    @Test
    @DisplayName("berganti tipe dengan isi kosong mengosongkan nilainya, tidak menyeret nilai tersandi")
    void bergantiTipe() {
        simpan("Alamat", "Secret", null, "abc");
        simpan("Alamat", "Text", null, null);

        assertNull(tabel.baris.get("Alamat").get("valueText"));
        assertNull(layanan.nilaiAset(penyewa, "Alamat").get("value"));
    }

    @Test
    @DisplayName("nama pengguna hanya disimpan untuk Credential")
    void usernameHanyaUntukCredential() {
        simpan("Folder", "Text", "tidak-dipakai", "C:\\Data");

        assertNull(tabel.baris.get("Folder").get("username"));
        assertFalse(layanan.nilaiAset(penyewa, "Folder").containsKey("username"));
    }

    @Test
    @DisplayName("POST /api/credentials lama menghasilkan aset Credential yang sama")
    void jalurSimpanLama() {
        layanan.simpanKredensial(penyewa,
                Permintaan.Kredensial.dari(Map.of("name", "SAP", "username", "admin", "password", "p@ss")));

        assertEquals("Credential", tabel.baris.get("SAP").get("type"));
        assertEquals("p@ss", layanan.nilaiAset(penyewa, "SAP").get("value"));
        assertEquals(List.of("SAP"), layanan.kredensial(penyewa).stream().map(k -> k.get("name")).toList());
    }

    @Test
    @DisplayName("jalur kredensial lama tidak boleh menimpa aset lain yang bernama sama")
    void tidakMenimpaAsetLain() {
        simpan("SAP", "Text", null, "https://sap.local");

        ApiException e = assertThrows(ApiException.class, () -> layanan.simpanKredensial(penyewa,
                Permintaan.Kredensial.dari(Map.of("name", "SAP", "username", "admin", "password", "x"))));

        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("Text", tabel.baris.get("SAP").get("type"));
        assertEquals("https://sap.local", tabel.baris.get("SAP").get("valueText"));
    }

    @Test
    @DisplayName("Get Credential untuk aset yang bukan Credential dijawab 404")
    void bukanCredential() {
        simpan("SAP", "Text", null, "https://sap.local");

        ApiException e = assertThrows(ApiException.class, () -> layanan.nilaiKredensial(penyewa, "SAP"));

        assertEquals(HttpStatus.NOT_FOUND, e.status());
    }

    @Test
    @DisplayName("menghapus lewat jalur kredensial tidak mengenai aset lain")
    void hapusLewatJalurKredensial() {
        simpan("SAP", "Text", null, "https://sap.local");

        assertThrows(ApiException.class, () -> layanan.hapusKredensial(penyewa, "SAP"));
        assertTrue(tabel.baris.containsKey("SAP"));
    }

    // ---------- alat ----------

    private void simpan(String nama, String tipe, String pengguna, String nilai) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", nama);
        body.put("type", tipe);
        body.put("username", pengguna);
        body.put("value", nilai);

        layanan.simpanAset(penyewa, Permintaan.Aset.dari(body));
    }

    /**
     * Tabel assets di memori, meniru kueri VaultRepository satu per satu —
     * termasuk syarat {@code type = 'Credential'} pada jalur kredensial.
     */
    private static final class AsetPalsu extends VaultRepository {

        final Map<String, Map<String, Object>> baris = new LinkedHashMap<>();

        AsetPalsu() {
            super(null);
        }

        @Override
        public String tipeAset(UUID tenantId, String nama) {
            return baris.containsKey(nama) ? (String) baris.get(nama).get("type") : null;
        }

        @Override
        public Map<String, Object> nilaiAset(UUID tenantId, String nama) {
            return baris.get(nama) == null ? null : new HashMap<>(baris.get(nama));
        }

        @Override
        public void buatAset(UUID tenantId, String nama, String tipe, String pengguna, String nilai,
                             String keterangan, String cakupan) {
            Map<String, Object> b = new HashMap<>();
            b.put("name", nama);
            b.put("type", tipe);
            b.put("username", pengguna);
            b.put("valueText", nilai);
            b.put("description", keterangan);
            baris.put(nama, b);
        }

        @Override
        public void perbaruiAset(UUID tenantId, String nama, String tipe, String pengguna, String nilai,
                                 boolean pertahankanNilai, String keterangan, String cakupan) {
            Map<String, Object> b = baris.get(nama);
            b.put("type", tipe);
            b.put("username", pengguna);
            if (!pertahankanNilai) b.put("valueText", nilai);
            b.put("description", keterangan);
        }

        @Override
        public List<Map<String, Object>> kredensial(UUID tenantId) {
            return baris.values().stream().filter(b -> "Credential".equals(b.get("type"))).toList();
        }

        @Override
        public Map<String, Object> nilaiKredensial(UUID tenantId, String nama) {
            Map<String, Object> b = baris.get(nama);
            if (b == null || !"Credential".equals(b.get("type"))) return null;

            Map<String, Object> hasil = new HashMap<>();
            hasil.put("username", b.get("username"));
            hasil.put("passwordEnc", b.get("valueText"));
            return hasil;
        }

        @Override
        public int hapusKredensial(UUID tenantId, String nama) {
            Map<String, Object> b = baris.get(nama);
            if (b == null || !"Credential".equals(b.get("type"))) return 0;

            baris.remove(nama);
            return 1;
        }
    }
}
