package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.FolderRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.Izin;
import id.jakforge.forgehub.security.PemeriksaIzin;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aturan tentang folder: siapa melihat apa, dan bentuk pohonnya.
 *
 * <p>HAK di sini sederhana, dan sengaja begitu:
 *
 * <ul>
 *   <li>Peran yang boleh MENUGASKAN orang ke folder (folders.update) melihat
 *       semua folder bersama: ia toh bisa menugaskan dirinya sendiri ke mana
 *       pun, jadi menyembunyikan folder darinya tidak melindungi apa-apa.
 *       Administrator termasuk di sini.</li>
 *   <li>Pengguna lain hanya melihat folder tempat ia ditugaskan, ditambah
 *       Folder Saya miliknya sendiri.</li>
 *   <li>Folder Saya hanya terlihat oleh pemiliknya di bilah folder.
 *       Pengelola folder tetap bisa membukanya dari halaman pengelolaan,
 *       karena harus ada yang bisa membereskannya kalau pemiliknya sudah
 *       pergi.</li>
 *   <li>Membuat, mengubah, dan menghapus folder masing-masing butuh
 *       folders.create, folders.update, dan folders.delete.</li>
 * </ul>
 *
 * <p>Pembatasan itu berlaku untuk permintaan yang MENYEBUT folder — dasbor
 * selalu menyebutnya. Permintaan tanpa folder, yang dikirim Studio dan
 * JakRunner, tetap menjangkau seluruh penyewa seperti sebelum folder ada:
 * robot mencari aset dan antrean lewat nama, tanpa tahu foldernya.
 */
@Service
public class FolderService {

    /** Batas panjang nama, mengikuti kolomnya di V4. */
    private static final int PANJANG_NAMA = 120;
    private static final int PANJANG_KETERANGAN = 400;

    private final FolderRepository folders;
    private final PemeriksaIzin izin;

    public FolderService(FolderRepository folders, PemeriksaIzin izin) {
        this.folders = folders;
        this.izin = izin;
    }

    /** Melihat dan mengatur semua folder bersama — lihat keterangan kelas. */
    private boolean pengelola(ForgeHubPrincipal p) {
        return izin.boleh(p, "folders.update");
    }

    private void pastikan(ForgeHubPrincipal p, String perlu) {
        if (!izin.boleh(p, perlu)) throw Izin.tolak(perlu);
    }

    // -----------------------------------------------------------------
    // Hak
    // -----------------------------------------------------------------

    /**
     * Folder yang boleh dibuka seseorang, atau null yang berarti SEMUA.
     *
     * <p>null untuk pengelola folder, bukan himpunan berisi semua id: pemanggilnya
     * tidak perlu menyaring apa pun, dan folder yang baru dibuat sedetik lalu
     * tidak tertinggal di luar himpunan.
     */
    public Set<UUID> akses(ForgeHubPrincipal p) {
        if (pengelola(p)) return null;

        Set<UUID> hasil = new HashSet<>(folders.ditugaskan(p.tenantId(), p.userId()));

        Map<String, Object> pribadi = folders.pribadi(p.tenantId(), p.userId());
        if (pribadi != null) hasil.add(Db.uuid((String) pribadi.get("id")));

        return hasil;
    }

    /**
     * Folder dari parameter permintaan, sesudah diperiksa haknya.
     *
     * @return null kalau permintaannya tidak menyebut folder — artinya seluruh
     *         penyewa, seperti sebelum folder ada.
     */
    public UUID saring(ForgeHubPrincipal p, String folderId) {
        if (folderId == null || folderId.isBlank()) return null;

        UUID id = Db.uuid(folderId);
        if (id == null) throw ApiException.tidakAda("Folder tidak ada.");

        periksa(p, id);

        return id;
    }

    /**
     * Folder itu, kalau ada dan boleh dibuka.
     *
     * <p>Folder pribadi orang lain dijawab "tidak ada", bukan "tidak berhak":
     * jawaban kedua memberi tahu bahwa folder itu ada.
     */
    public Map<String, Object> periksa(ForgeHubPrincipal p, UUID id) {
        Map<String, Object> folder = folders.satu(p.tenantId(), id);

        if (folder == null) throw ApiException.tidakAda("Folder tidak ada.");
        if (pengelola(p)) return folder;

        Object pemilik = folder.get("ownerId");

        if (pemilik != null) {
            if (!p.userId().toString().equals(pemilik)) throw ApiException.tidakAda("Folder tidak ada.");
            return folder;
        }

        if (!folders.ditugaskan(p.tenantId(), p.userId()).contains(id)) {
            throw ApiException.tidakBerhak("Anda tidak ditugaskan ke folder ini.");
        }

        return folder;
    }

    // -----------------------------------------------------------------
    // Pohon
    // -----------------------------------------------------------------

    /**
     * Isi bilah folder: folder bersama yang boleh dilihat, dan Folder Saya.
     *
     * <p>Folder yang tidak boleh dibuka tetap dikirim kalau ia LELUHUR folder
     * yang boleh dibuka, dengan {@code accessible: false}. Tanpa itu, orang
     * yang hanya ditugaskan ke "Keuangan / Tagihan" melihat "Tagihan" melayang
     * di akar, terlepas dari tempatnya di pohon.
     */
    public Map<String, Object> daftar(ForgeHubPrincipal p) {
        List<Map<String, Object>> semua = folders.semua(p.tenantId());
        Set<UUID> boleh = akses(p);

        List<Map<String, Object>> terlihat = new ArrayList<>();

        if (boleh == null) {
            for (Map<String, Object> f : semua) {
                f.put("accessible", true);
                terlihat.add(f);
            }
        } else {
            Map<String, Map<String, Object>> perId = new HashMap<>();
            for (Map<String, Object> f : semua) perId.put((String) f.get("id"), f);

            Set<String> tampil = new HashSet<>();

            for (Map<String, Object> f : semua) {
                if (!boleh.contains(Db.uuid((String) f.get("id")))) continue;

                // Naik sampai akar, menandai setiap leluhur supaya ikut tampil.
                for (Map<String, Object> x = f; x != null; x = perId.get((String) x.get("parentId"))) {
                    if (!tampil.add((String) x.get("id"))) break;
                }
            }

            for (Map<String, Object> f : semua) {
                if (!tampil.contains((String) f.get("id"))) continue;

                f.put("accessible", boleh.contains(Db.uuid((String) f.get("id"))));
                terlihat.add(f);
            }
        }

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("folders", terlihat);
        hasil.put("personal", folders.pribadi(p.tenantId(), p.userId()));
        hasil.put("canManage", izin.boleh(p, "folders.create"));

        return hasil;
    }

    /** Halaman pengelolaan: semua folder, termasuk folder pribadi setiap orang. */
    public List<Map<String, Object>> kelola(ForgeHubPrincipal p) {
        pastikan(p, "folders.read");

        return folders.kelola(p.tenantId());
    }

    /**
     * Folder Saya milik orang yang meminta, dibuat kalau belum ada.
     *
     * <p>Dibuat saat pertama kali DIBUKA, bukan saat penggunanya dibuat:
     * kebanyakan orang tidak pernah memakainya, dan seratus folder kosong
     * hanya memenuhi halaman pengelolaan.
     */
    @Transactional
    public Map<String, Object> pribadi(ForgeHubPrincipal p) {
        Map<String, Object> ada = folders.pribadi(p.tenantId(), p.userId());
        if (ada != null) return ada;

        folders.buatPribadi(p.tenantId(), p.userId());

        return folders.pribadi(p.tenantId(), p.userId());
    }

    // -----------------------------------------------------------------
    // Membuat, mengubah, menghapus
    // -----------------------------------------------------------------

    @Transactional
    public Map<String, Object> buat(ForgeHubPrincipal p, Permintaan.Folder minta) {
        pastikan(p, "folders.create");

        String nama = periksaNama(minta.name());
        UUID induk = indukSah(p.tenantId(), minta.parentId());

        if (folders.namaDipakai(p.tenantId(), induk, nama, null)) {
            throw ApiException.sudahAda("Folder '" + nama + "' sudah ada di tempat itu.");
        }

        UUID id = Db.newId();

        try {
            folders.buat(id, p.tenantId(), induk, nama, keterangan(minta.description()));
        } catch (DuplicateKeyException e) {
            // Orang lain membuat nama yang sama di antara pemeriksaan dan
            // penyimpanan. Jawabannya sama dengan pemeriksaan di atas.
            throw ApiException.sudahAda("Folder '" + nama + "' sudah ada di tempat itu.");
        }

        if (induk != null) folders.salinPenugasan(p.tenantId(), induk, id);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("id", id.toString());

        return hasil;
    }

    /**
     * Ganti nama, keterangan, atau induk.
     *
     * <p>Folder bawaan boleh berganti nama tapi tetap di akar: folder itu
     * tempat jatuhnya segala yang datang tanpa folder, dan tempat seperti itu
     * tidak boleh tersembunyi di dalam cabang lain.
     */
    @Transactional
    public void ubah(ForgeHubPrincipal p, String idTeks, Permintaan.Folder minta) {
        pastikan(p, "folders.update");

        UUID id = uuid(idTeks);
        Map<String, Object> folder = folders.satu(p.tenantId(), id);

        if (folder == null) throw ApiException.tidakAda("Folder tidak ada.");

        if (folder.get("ownerId") != null) {
            throw ApiException.salah("Folder Saya tidak bisa diganti nama atau dipindah.");
        }

        // Keterangan kosong berarti dihapus: dialog sunting selalu mengirimnya,
        // dan mengosongkan isiannya memang dimaksudkan untuk membuangnya.
        String nama = minta.name() == null ? (String) folder.get("name") : periksaNama(minta.name());
        String ket = keterangan(minta.description());

        UUID induk = Db.uuid((String) folder.get("parentId"));

        if (minta.gantiInduk()) {
            UUID baru = indukSah(p.tenantId(), minta.parentId());

            if (Boolean.TRUE.equals(folder.get("isDefault")) && baru != null) {
                throw ApiException.salah("Folder bawaan harus tetap di akar.");
            }

            if (baru != null && (baru.equals(id) || folders.keturunan(p.tenantId(), id).contains(baru))) {
                throw ApiException.salah("Folder tidak bisa dipindah ke dalam dirinya sendiri.");
            }

            induk = baru;
        }

        if (folders.namaDipakai(p.tenantId(), induk, nama, id)) {
            throw ApiException.sudahAda("Folder '" + nama + "' sudah ada di tempat itu.");
        }

        try {
            folders.ubah(p.tenantId(), id, nama, ket, induk);
        } catch (DuplicateKeyException e) {
            throw ApiException.sudahAda("Folder '" + nama + "' sudah ada di tempat itu.");
        }
    }

    /**
     * Hapus folder yang sudah KOSONG.
     *
     * <p>Folder yang masih berisi ditolak, bukan dikosongkan diam-diam:
     * menghapus satu folder tidak boleh sekaligus menghapus proses, aset, dan
     * antrean yang mungkin masih dipakai robot di tempat lain.
     *
     * <p>Riwayat pekerjaannya tidak menghalangi. Riwayat itu dipindah ke
     * induknya — atau ke folder bawaan untuk folder di akar — supaya tetap
     * bisa dibaca.
     */
    @Transactional
    public void hapus(ForgeHubPrincipal p, String idTeks) {
        pastikan(p, "folders.delete");

        UUID id = uuid(idTeks);
        Map<String, Object> folder = folders.satu(p.tenantId(), id);

        if (folder == null) throw ApiException.tidakAda("Folder tidak ada.");

        if (Boolean.TRUE.equals(folder.get("isDefault"))) {
            throw ApiException.salah("Folder bawaan tidak bisa dihapus.");
        }

        if (folders.adaAnak(p.tenantId(), id)) {
            throw ApiException.salah("Folder '" + folder.get("name") + "' masih punya subfolder. Pindahkan atau hapus subfoldernya dulu.");
        }

        if (folders.jumlahIsi(p.tenantId(), id) > 0) {
            throw ApiException.salah("Folder '" + folder.get("name")
                    + "' masih berisi proses, pemicu, antrean, aset, atau ember penyimpanan. Pindahkan atau hapus isinya dulu.");
        }

        UUID induk = Db.uuid((String) folder.get("parentId"));
        folders.pindahkanPekerjaan(p.tenantId(), id, induk != null ? induk : folders.bawaan(p.tenantId()));

        folders.hapus(p.tenantId(), id);
    }

    // -----------------------------------------------------------------
    // Penugasan
    // -----------------------------------------------------------------

    /** Pengguna dan robot yang ditugaskan; boleh dilihat siapa pun yang boleh membuka foldernya. */
    public Map<String, Object> anggota(ForgeHubPrincipal p, String idTeks) {
        UUID id = uuid(idTeks);
        Map<String, Object> folder = periksa(p, id);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("folder", folder);
        hasil.put("users", folders.pengguna(p.tenantId(), id));
        hasil.put("robots", folders.robot(p.tenantId(), id));
        hasil.put("canManageUsers", pengelola(p) && folder.get("ownerId") == null);
        hasil.put("canManageRobots", bolehAturRobot(p, folder));

        // Id pemilik tidak ikut keluar; yang perlu diketahui layar sudah ada di "personal".
        folder.remove("ownerId");

        return hasil;
    }

    @Transactional
    public void tugaskanPengguna(ForgeHubPrincipal p, String idTeks, String username) {
        pastikan(p, "folders.update");

        UUID id = folderBersama(p, idTeks);
        UUID pengguna = folders.idPengguna(p.tenantId(), wajib(username, "username"));

        if (pengguna == null) throw ApiException.tidakAda("Pengguna tidak ada.");

        folders.tugaskanPengguna(p.tenantId(), id, pengguna);
    }

    @Transactional
    public void lepasPengguna(ForgeHubPrincipal p, String idTeks, String username) {
        pastikan(p, "folders.update");

        UUID id = folderBersama(p, idTeks);
        UUID pengguna = folders.idPengguna(p.tenantId(), username);

        if (pengguna == null || folders.lepasPengguna(p.tenantId(), id, pengguna) == 0) {
            throw ApiException.tidakAda("Pengguna itu tidak ditugaskan ke folder ini.");
        }
    }

    @Transactional
    public void tugaskanRobot(ForgeHubPrincipal p, String idTeks, String nama) {
        UUID id = uuid(idTeks);
        Map<String, Object> folder = periksa(p, id);

        if (!bolehAturRobot(p, folder)) throw Izin.tolak("folders.update");

        UUID robot = folders.idRobot(p.tenantId(), wajib(nama, "robotName"));

        if (robot == null) throw ApiException.tidakAda("Robot '" + nama + "' tidak ada.");

        folders.tugaskanRobot(p.tenantId(), id, robot);
    }

    @Transactional
    public void lepasRobot(ForgeHubPrincipal p, String idTeks, String nama) {
        UUID id = uuid(idTeks);
        Map<String, Object> folder = periksa(p, id);

        if (!bolehAturRobot(p, folder)) throw Izin.tolak("folders.update");

        UUID robot = folders.idRobot(p.tenantId(), nama);

        if (robot == null || folders.lepasRobot(p.tenantId(), id, robot) == 0) {
            throw ApiException.tidakAda("Robot itu tidak ditugaskan ke folder ini.");
        }
    }

    /**
     * Robot folder bersama diatur pengelola folder. Robot Folder Saya diatur
     * pemiliknya: itu tempat kerja pribadinya, dan menunggu Administrator
     * hanya untuk menjalankan prosesnya sendiri tidak masuk akal.
     */
    private boolean bolehAturRobot(ForgeHubPrincipal p, Map<String, Object> folder) {
        Object pemilik = folder.get("ownerId");

        if (pemilik != null) return p.userId().toString().equals(pemilik) || pengelola(p);

        return pengelola(p);
    }

    // -----------------------------------------------------------------
    // Alat
    // -----------------------------------------------------------------

    private UUID folderBersama(ForgeHubPrincipal p, String idTeks) {
        UUID id = uuid(idTeks);
        Map<String, Object> folder = folders.satu(p.tenantId(), id);

        if (folder == null) throw ApiException.tidakAda("Folder tidak ada.");

        if (folder.get("ownerId") != null) {
            throw ApiException.salah("Folder Saya hanya milik pemiliknya; pengguna lain tidak bisa ditugaskan ke sana.");
        }

        return id;
    }

    /** Induk yang diminta: harus ada, dan bukan folder pribadi. */
    private UUID indukSah(UUID tenantId, String parentId) {
        if (parentId == null) return null;

        UUID induk = Db.uuid(parentId);
        Map<String, Object> folder = induk == null ? null : folders.satu(tenantId, induk);

        if (folder == null) throw ApiException.tidakAda("Folder induk tidak ada.");

        if (folder.get("ownerId") != null) {
            throw ApiException.salah("Folder Saya tidak bisa punya subfolder.");
        }

        return induk;
    }

    /**
     * Nama folder: wajib, tidak terlalu panjang, dan tanpa garis miring.
     *
     * <p>Garis miring dipakai untuk menuliskan jalurnya — "Keuangan / Tagihan"
     * — dan nama yang memuatnya membuat jalur itu terbaca sebagai folder lain.
     */
    private static String periksaNama(String nama) {
        if (nama == null || nama.isBlank()) throw ApiException.salah("Nama folder wajib diisi.");

        String bersih = nama.trim();

        if (bersih.length() > PANJANG_NAMA) {
            throw ApiException.salah("Nama folder paling panjang " + PANJANG_NAMA + " karakter.");
        }

        if (bersih.contains("/") || bersih.contains("\\")) {
            throw ApiException.salah("Nama folder tidak boleh memuat garis miring.");
        }

        return bersih;
    }

    private static String keterangan(String teks) {
        if (teks == null) return null;

        String bersih = teks.trim();
        if (bersih.isEmpty()) return null;

        if (bersih.length() > PANJANG_KETERANGAN) {
            throw ApiException.salah("Keterangan paling panjang " + PANJANG_KETERANGAN + " karakter.");
        }

        return bersih;
    }

    private static String wajib(String nilai, String medan) {
        if (nilai == null || nilai.isBlank()) throw ApiException.salah(medan + " wajib diisi.");
        return nilai.trim();
    }

    private static UUID uuid(String teks) {
        UUID id = Db.uuid(teks);
        if (id == null) throw ApiException.tidakAda("Folder tidak ada.");
        return id;
    }
}
