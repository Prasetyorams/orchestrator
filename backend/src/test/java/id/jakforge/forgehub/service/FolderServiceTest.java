package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.repository.FolderRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hak dan bentuk pohon folder.
 *
 * <p>Pohonnya:
 *
 * <pre>
 *   Shared (bawaan)
 *   Keuangan
 *   └── Tagihan
 *       └── Arsip
 *   Gudang
 * </pre>
 *
 * dan seorang Automation User yang hanya ditugaskan ke Tagihan.
 */
class FolderServiceTest {

    private final UUID penyewa = UUID.randomUUID();

    private final ForgeHubPrincipal admin =
            new ForgeHubPrincipal(UUID.randomUUID(), penyewa, "FH_Admin", "Administrator");
    private final ForgeHubPrincipal budi =
            new ForgeHubPrincipal(UUID.randomUUID(), penyewa, "budi", "Automation User");

    private final FolderPalsu tabel = new FolderPalsu();
    // Administrator boleh segalanya; Automation User tidak punya izin folder apa pun.
    private final FolderService layanan =
            new FolderService(tabel, (p, izin) -> "Administrator".equals(p.role()));

    private final UUID shared = tabel.tambah(null, "Shared", true, null);
    private final UUID keuangan = tabel.tambah(null, "Keuangan", false, null);
    private final UUID tagihan = tabel.tambah(keuangan, "Tagihan", false, null);
    private final UUID arsip = tabel.tambah(tagihan, "Arsip", false, null);
    private final UUID gudang = tabel.tambah(null, "Gudang", false, null);

    {
        tabel.pengguna.add(tagihan + "/" + budi.userId());
    }

    // ---------- pohon ----------

    @Test
    @DisplayName("Administrator melihat semua folder bersama, semuanya bisa dibuka")
    void adminMelihatSemua() {
        List<Map<String, Object>> folder = daftar(admin);

        assertEquals(5, folder.size());
        assertTrue(folder.stream().allMatch(f -> Boolean.TRUE.equals(f.get("accessible"))));
    }

    @Test
    @DisplayName("pengguna lain melihat foldernya beserta leluhurnya, leluhur itu tidak bisa dibuka")
    void penggunaMelihatCabangnya() {
        Map<String, Boolean> terlihat = new HashMap<>();
        for (Map<String, Object> f : daftar(budi)) terlihat.put((String) f.get("name"), (Boolean) f.get("accessible"));

        assertEquals(Map.of("Keuangan", false, "Tagihan", true), terlihat);
    }

    @Test
    @DisplayName("Folder Saya milik sendiri ikut; milik orang lain tidak pernah muncul di pohon")
    void folderPribadi() {
        layanan.pribadi(budi);

        assertEquals("Folder Saya", ((Map<?, ?>) layanan.daftar(budi).get("personal")).get("name"));
        assertNull(layanan.daftar(admin).get("personal"));
        assertTrue(daftar(admin).stream().noneMatch(f -> "Folder Saya".equals(f.get("name"))));
    }

    // ---------- hak ----------

    @Test
    @DisplayName("folder yang tidak ditugaskan ditolak 403; Folder Saya orang lain dijawab 404")
    void hakMembuka() {
        assertStatus(HttpStatus.FORBIDDEN, () -> layanan.saring(budi, gudang.toString()));
        assertStatus(HttpStatus.FORBIDDEN, () -> layanan.saring(budi, keuangan.toString()));
        assertEquals(tagihan, layanan.saring(budi, tagihan.toString()));

        UUID milikAdmin = UUID.fromString((String) layanan.pribadi(admin).get("id"));
        assertStatus(HttpStatus.NOT_FOUND, () -> layanan.saring(budi, milikAdmin.toString()));

        UUID milikBudi = UUID.fromString((String) layanan.pribadi(budi).get("id"));
        assertEquals(milikBudi, layanan.saring(budi, milikBudi.toString()));
    }

    @Test
    @DisplayName("tanpa folder berarti seluruh penyewa; id yang rusak dijawab 404")
    void tanpaFolder() {
        assertNull(layanan.saring(budi, null));
        assertNull(layanan.saring(budi, " "));
        assertStatus(HttpStatus.NOT_FOUND, () -> layanan.saring(budi, "bukan-uuid"));
    }

    // ---------- membuat dan mengubah ----------

    @Test
    @DisplayName("hanya Administrator yang membuat folder")
    void buatHanyaAdmin() {
        assertStatus(HttpStatus.FORBIDDEN, () -> layanan.buat(budi, folder("Baru", null)));
    }

    @Test
    @DisplayName("subfolder baru mewarisi pengguna dan robot induknya")
    void subfolderMewarisi() {
        tabel.robot.add(tagihan + "/" + UUID.randomUUID());

        Map<String, Object> hasil = layanan.buat(admin, folder("Bulanan", tagihan.toString()));
        UUID baru = UUID.fromString((String) hasil.get("id"));

        assertTrue(tabel.pengguna.contains(baru + "/" + budi.userId()));
        assertEquals(1, tabel.robot.stream().filter(r -> r.startsWith(baru + "/")).count());
    }

    @Test
    @DisplayName("nama kembar di tempat yang sama ditolak 409, tanpa membedakan huruf besar")
    void namaKembar() {
        assertStatus(HttpStatus.CONFLICT, () -> layanan.buat(admin, folder("keuangan", null)));

        // Di induk lain, nama yang sama boleh.
        layanan.buat(admin, folder("Keuangan", gudang.toString()));
    }

    @Test
    @DisplayName("nama dengan garis miring ditolak: garis miring dipakai untuk menuliskan jalur")
    void garisMiring() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> layanan.buat(admin, folder("A/B", null)));
    }

    @Test
    @DisplayName("folder tidak bisa dipindah ke dalam cabangnya sendiri")
    void pindahKeCabangSendiri() {
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> layanan.ubah(admin, keuangan.toString(), folder("Keuangan", arsip.toString())));
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> layanan.ubah(admin, keuangan.toString(), folder("Keuangan", keuangan.toString())));

        layanan.ubah(admin, arsip.toString(), folder("Arsip", gudang.toString()));
        assertEquals(gudang.toString(), tabel.folder.get(arsip).get("parentId"));
    }

    @Test
    @DisplayName("folder bawaan boleh berganti nama tapi tetap di akar")
    void bawaanDiAkar() {
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> layanan.ubah(admin, shared.toString(), folder("Shared", gudang.toString())));

        layanan.ubah(admin, shared.toString(), folder("Bersama", null));
        assertEquals("Bersama", tabel.folder.get(shared).get("name"));
    }

    // ---------- menghapus ----------

    @Test
    @DisplayName("folder bawaan, folder bersubfolder, dan folder berisi tidak bisa dihapus")
    void hapusDitolak() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> layanan.hapus(admin, shared.toString()));
        assertStatus(HttpStatus.BAD_REQUEST, () -> layanan.hapus(admin, tagihan.toString()));

        tabel.isi.put(gudang, 2L);
        assertStatus(HttpStatus.BAD_REQUEST, () -> layanan.hapus(admin, gudang.toString()));
    }

    @Test
    @DisplayName("folder kosong terhapus, dan riwayat pekerjaannya pindah ke induknya")
    void hapusKosong() {
        layanan.hapus(admin, arsip.toString());

        assertFalse(tabel.folder.containsKey(arsip));
        assertEquals(List.of(arsip + "->" + tagihan), tabel.pekerjaanDipindah);
    }

    @Test
    @DisplayName("riwayat folder akar pindah ke folder bawaan")
    void hapusAkar() {
        layanan.hapus(admin, gudang.toString());

        assertEquals(List.of(gudang + "->" + shared), tabel.pekerjaanDipindah);
    }

    // ---------- penugasan ----------

    @Test
    @DisplayName("robot Folder Saya diatur pemiliknya sendiri, robot folder bersama hanya oleh Administrator")
    void aturRobot() {
        tabel.idRobot.put("PC-Budi", UUID.randomUUID());
        String pribadi = (String) layanan.pribadi(budi).get("id");

        layanan.tugaskanRobot(budi, pribadi, "PC-Budi");
        assertTrue(tabel.robot.contains(pribadi + "/" + tabel.idRobot.get("PC-Budi")));

        assertStatus(HttpStatus.FORBIDDEN, () -> layanan.tugaskanRobot(budi, tagihan.toString(), "PC-Budi"));
    }

    @Test
    @DisplayName("pengguna tidak bisa ditugaskan ke Folder Saya orang lain")
    void penggunaKeFolderPribadi() {
        tabel.idPengguna.put("budi", budi.userId());
        String pribadi = (String) layanan.pribadi(admin).get("id");

        assertStatus(HttpStatus.BAD_REQUEST, () -> layanan.tugaskanPengguna(admin, pribadi, "budi"));
    }

    // ---------- alat ----------

    private List<Map<String, Object>> daftar(ForgeHubPrincipal p) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> folder = (List<Map<String, Object>>) layanan.daftar(p).get("folders");
        return folder;
    }

    private static Permintaan.Folder folder(String nama, String induk) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", nama);
        body.put("parentId", induk);
        return Permintaan.Folder.dari(body);
    }

    private static void assertStatus(HttpStatus status, Executable aksi) {
        assertEquals(status, assertThrows(ApiException.class, aksi).status());
    }

    /**
     * Tabel folder di memori, meniru kueri FolderRepository yang dipakai
     * layanannya. Baris berbentuk seperti keluaran Db: id sebagai teks.
     */
    private static final class FolderPalsu extends FolderRepository {

        final Map<UUID, Map<String, Object>> folder = new LinkedHashMap<>();
        final Set<String> pengguna = new HashSet<>();
        final Set<String> robot = new HashSet<>();
        final Map<UUID, Long> isi = new HashMap<>();
        final Map<String, UUID> idPengguna = new HashMap<>();
        final Map<String, UUID> idRobot = new HashMap<>();
        final List<String> pekerjaanDipindah = new ArrayList<>();

        FolderPalsu() {
            super(null);
        }

        UUID tambah(UUID induk, String nama, boolean bawaan, UUID pemilik) {
            UUID id = UUID.randomUUID();

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", id.toString());
            f.put("parentId", induk == null ? null : induk.toString());
            f.put("name", nama);
            f.put("description", null);
            f.put("isDefault", bawaan);
            f.put("personal", pemilik != null);
            f.put("ownerId", pemilik == null ? null : pemilik.toString());
            folder.put(id, f);

            return id;
        }

        private static Map<String, Object> tanpaPemilik(Map<String, Object> f) {
            Map<String, Object> salin = new LinkedHashMap<>(f);
            salin.remove("ownerId");
            return salin;
        }

        @Override
        public List<Map<String, Object>> semua(UUID tenantId) {
            return folder.values().stream().filter(f -> f.get("ownerId") == null)
                    .map(FolderPalsu::tanpaPemilik).toList();
        }

        @Override
        public Map<String, Object> pribadi(UUID tenantId, UUID userId) {
            return folder.values().stream().filter(f -> userId.toString().equals(f.get("ownerId")))
                    .findFirst().map(FolderPalsu::tanpaPemilik).orElse(null);
        }

        @Override
        public Map<String, Object> satu(UUID tenantId, UUID id) {
            Map<String, Object> f = folder.get(id);
            return f == null ? null : new LinkedHashMap<>(f);
        }

        @Override
        public Set<UUID> ditugaskan(UUID tenantId, UUID userId) {
            Set<UUID> hasil = new HashSet<>();
            for (String p : pengguna) {
                if (p.endsWith("/" + userId)) hasil.add(UUID.fromString(p.substring(0, 36)));
            }
            return hasil;
        }

        @Override
        public UUID bawaan(UUID tenantId) {
            return folder.entrySet().stream().filter(e -> Boolean.TRUE.equals(e.getValue().get("isDefault")))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
        }

        @Override
        public Set<UUID> keturunan(UUID tenantId, UUID id) {
            Set<UUID> hasil = new HashSet<>();
            for (Map.Entry<UUID, Map<String, Object>> e : folder.entrySet()) {
                if (id.toString().equals(e.getValue().get("parentId"))) {
                    hasil.add(e.getKey());
                    hasil.addAll(keturunan(tenantId, e.getKey()));
                }
            }
            return hasil;
        }

        @Override
        public boolean namaDipakai(UUID tenantId, UUID parentId, String nama, UUID kecuali) {
            return folder.entrySet().stream().anyMatch(e -> e.getValue().get("ownerId") == null
                    && java.util.Objects.equals(e.getValue().get("parentId"), parentId == null ? null : parentId.toString())
                    && ((String) e.getValue().get("name")).equalsIgnoreCase(nama)
                    && !e.getKey().equals(kecuali));
        }

        @Override
        public boolean adaAnak(UUID tenantId, UUID id) {
            return folder.values().stream().anyMatch(f -> id.toString().equals(f.get("parentId")));
        }

        @Override
        public long jumlahIsi(UUID tenantId, UUID id) {
            return isi.getOrDefault(id, 0L);
        }

        @Override
        public void buat(UUID id, UUID tenantId, UUID parentId, String nama, String keterangan) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", id.toString());
            f.put("parentId", parentId == null ? null : parentId.toString());
            f.put("name", nama);
            f.put("description", keterangan);
            f.put("isDefault", false);
            f.put("personal", false);
            f.put("ownerId", null);
            folder.put(id, f);
        }

        @Override
        public void buatPribadi(UUID tenantId, UUID userId) {
            tambah(null, "Folder Saya", false, userId);
        }

        @Override
        public void ubah(UUID tenantId, UUID id, String nama, String keterangan, UUID parentId) {
            Map<String, Object> f = folder.get(id);
            f.put("name", nama);
            f.put("description", keterangan);
            f.put("parentId", parentId == null ? null : parentId.toString());
        }

        @Override
        public void salinPenugasan(UUID tenantId, UUID dari, UUID ke) {
            for (String p : new ArrayList<>(pengguna)) if (p.startsWith(dari + "/")) pengguna.add(ke + p.substring(36));
            for (String r : new ArrayList<>(robot)) if (r.startsWith(dari + "/")) robot.add(ke + r.substring(36));
        }

        @Override
        public void pindahkanPekerjaan(UUID tenantId, UUID dari, UUID ke) {
            pekerjaanDipindah.add(dari + "->" + ke);
        }

        @Override
        public int hapus(UUID tenantId, UUID id) {
            return folder.remove(id) == null ? 0 : 1;
        }

        @Override
        public UUID idPengguna(UUID tenantId, String username) {
            return idPengguna.get(username);
        }

        @Override
        public UUID idRobot(UUID tenantId, String nama) {
            return idRobot.get(nama);
        }

        @Override
        public int tugaskanPengguna(UUID tenantId, UUID folderId, UUID userId) {
            return pengguna.add(folderId + "/" + userId) ? 1 : 0;
        }

        @Override
        public int tugaskanRobot(UUID tenantId, UUID folderId, UUID robotId) {
            return robot.add(folderId + "/" + robotId) ? 1 : 0;
        }
    }
}
