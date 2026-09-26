package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.DashboardRepository;
import id.jakforge.forgehub.repository.FolderRepository;
import id.jakforge.forgehub.repository.RobotRepository;
import id.jakforge.forgehub.repository.UserRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.Izin;
import id.jakforge.forgehub.security.JwtService;
import id.jakforge.forgehub.security.Passwords;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pengguna, peran, penyewa, lisensi, dan setelan. */
@Service
public class AdminService {

    /** Mengikuti kolom users.role (VARCHAR 48): nama peran disimpan di sana. */
    private static final int PANJANG_NAMA_PERAN = 48;
    private static final int PANJANG_KETERANGAN = 400;

    private final UserRepository pengguna;
    private final RobotRepository robots;
    private final DashboardRepository ringkasan;
    private final FolderRepository folders;
    private final JwtService jwt;
    private final Izin izin;
    private final String zonaTampilan;

    public AdminService(UserRepository pengguna, RobotRepository robots,
                        DashboardRepository ringkasan, FolderRepository folders, JwtService jwt, Izin izin,
                        @Value("${forgehub.display-timezone:UTC}") String zonaTampilan) {
        this.pengguna = pengguna;
        this.robots = robots;
        this.ringkasan = ringkasan;
        this.folders = folders;
        this.jwt = jwt;
        this.izin = izin;
        this.zonaTampilan = zonaTampilan;
    }

    public List<Map<String, Object>> penyewa() {
        return pengguna.penyewa();
    }

    public List<Map<String, Object>> daftarPengguna(UUID tenantId) {
        return pengguna.semua(tenantId);
    }

    /**
     * Simpan pengguna.
     *
     * <p>Pembatasan diperiksa DI SINI, bukan diserahkan ke antarmuka: tombol
     * yang disembunyikan tetap bisa dilewati siapa pun yang memanggil API-nya
     * langsung.
     *
     * <ul>
     *   <li>Membuat butuh users.create, mengubah butuh users.update.</li>
     *   <li>Perannya harus ada, dan tidak boleh lebih luas dari peran yang
     *       menyimpannya — begitu juga peran LAMA pengguna yang disunting.
     *       Tanpa itu, pengelola pengguna bisa mengangkat dirinya sendiri atau
     *       temannya menjadi Administrator.</li>
     *   <li>Administrator aktif terakhir tidak bisa diturunkan atau
     *       dinonaktifkan: penyewa tanpa Administrator tidak bisa diurus lagi.</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> simpanPengguna(ForgeHubPrincipal p, Permintaan.Pengguna minta) {
        if (minta.username() == null) throw ApiException.salah("Nama pengguna wajib diisi.");

        UUID tenantId = p.tenantId();
        boolean sudahAda = pengguna.ada(tenantId, minta.username());

        izin.perlu(p, sudahAda ? "users.update" : "users.create");

        String peranBaru = minta.role() != null ? peranSah(p, tenantId, minta.role())
                : sudahAda ? null : peranSah(p, tenantId, "Automation User");

        if (sudahAda) {
            String peranLama = pengguna.peranPengguna(tenantId, minta.username());
            pastikanTercakup(p, tenantId, peranLama);

            boolean tetapAdministrator = minta.isActive()
                    && Izin.ADMINISTRATOR.equals(peranBaru != null ? peranBaru : peranLama);

            if (!tetapAdministrator && pengguna.administratorAktif(tenantId, minta.username())
                    && pengguna.jumlahAdministratorAktif(tenantId) <= 1) {
                throw ApiException.salah("Ini satu-satunya Administrator yang tersisa.");
            }

            pengguna.perbarui(tenantId, minta.username(), minta.displayName(),
                    minta.email(), peranBaru, minta.isActive());

            // Kata sandi hanya diganti kalau memang dikirim. Tanpa syarat ini,
            // menyunting alamat surel akan diam-diam mengosongkan sandinya —
            // dan yang bersangkutan baru tahu saat gagal masuk besok pagi.
            if (minta.password() != null && !minta.password().isEmpty()) {
                if (minta.password().length() < 6) {
                    throw ApiException.salah("Kata sandi minimal 6 karakter.");
                }

                pengguna.gantiSandiPengguna(tenantId, minta.username(),
                        Passwords.hash(minta.password()));
            }
        } else {
            if (minta.password() == null || minta.password().length() < 6) {
                throw ApiException.salah("Pengguna baru butuh kata sandi minimal 6 karakter.");
            }

            pengguna.buat(tenantId, minta.username(), Passwords.hash(minta.password()),
                    minta.displayName() == null ? minta.username() : minta.displayName(),
                    minta.email(), peranBaru, minta.isActive());
        }

        // Peran atau status aktif yang baru berlaku sekarang, bukan sepuluh
        // detik lagi (lihat Izin).
        izin.lupakan();

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("created", !sudahAda);

        return hasil;
    }

    @Transactional
    public void hapusPengguna(ForgeHubPrincipal p, String username) {
        // Menghapus diri sendiri akan mengunci orangnya keluar dari ForgeHub
        // miliknya sendiri, dan tidak ada jalan masuk lain untuk membatalkannya.
        if (username.equalsIgnoreCase(p.username())) {
            throw ApiException.salah("Tidak bisa menghapus akun yang sedang dipakai.");
        }

        UUID tenantId = p.tenantId();

        pastikanTercakup(p, tenantId, pengguna.peranPengguna(tenantId, username));

        // Penyewa tanpa satu pun administrator aktif tidak bisa diurus lagi:
        // tidak ada yang bisa membuat administrator baru, dan tidak ada pintu
        // belakang untuk memperbaikinya.
        if (pengguna.administrator(tenantId, username)
                && pengguna.jumlahAdministratorAktif(tenantId) <= 1) {

            throw ApiException.salah("Ini satu-satunya Administrator yang tersisa.");
        }

        hapusFolderPribadi(tenantId, username);

        if (pengguna.hapus(tenantId, username) == 0) {
            throw ApiException.tidakAda("Pengguna tidak ada.");
        }

        izin.lupakan();
    }

    /**
     * Folder Saya milik pengguna yang akan dihapus.
     *
     * <p>Yang kosong ikut dihapus; riwayat pekerjaannya pindah ke folder
     * bawaan supaya tetap bisa dibaca. Yang masih berisi MENGHENTIKAN
     * penghapusan: proses dan aset di sana bisa saja masih dijalankan robot,
     * dan tidak ada orang lain yang bisa melihat folder itu untuk
     * menyelamatkan isinya.
     */
    private void hapusFolderPribadi(UUID tenantId, String username) {
        UUID userId = folders.idPengguna(tenantId, username);
        if (userId == null) return;

        Map<String, Object> pribadi = folders.pribadi(tenantId, userId);
        if (pribadi == null) return;

        UUID folderId = Db.uuid((String) pribadi.get("id"));

        if (folders.jumlahIsi(tenantId, folderId) > 0) {
            throw ApiException.salah("Folder Saya milik '" + username
                    + "' masih berisi proses, pemicu, antrean, aset, atau ember penyimpanan. Pindahkan atau hapus isinya dulu.");
        }

        folders.pindahkanPekerjaan(tenantId, folderId, folders.bawaan(tenantId));
        folders.hapus(tenantId, folderId);
    }

    // -----------------------------------------------------------------
    // Peran
    // -----------------------------------------------------------------

    /** {@code locked}: peran Administrator, yang tidak bisa diubah atau dihapus. */
    public List<Map<String, Object>> peran(UUID tenantId) {
        List<Map<String, Object>> semua = pengguna.peran(tenantId);

        for (Map<String, Object> r : semua) r.put("locked", Izin.ADMINISTRATOR.equals(r.get("name")));

        return semua;
    }

    /** Katalog izin untuk matriks di layar Peran. */
    public List<Map<String, Object>> katalogIzin() {
        return Izin.KATALOG.stream()
                .map(s -> {
                    Map<String, Object> baris = new LinkedHashMap<>();
                    baris.put("resource", s.kunci());
                    baris.put("actions", s.aksi());
                    return baris;
                })
                .toList();
    }

    @Transactional
    public void buatPeran(ForgeHubPrincipal p, Permintaan.Peran minta) {
        UUID tenantId = p.tenantId();
        String nama = namaPeran(minta.name());

        if (pengguna.peranSatu(tenantId, nama) != null) {
            throw ApiException.sudahAda("Peran '" + nama + "' sudah ada.");
        }

        List<String> daftar = izinBaru(p, minta.permissions() == null ? List.of() : minta.permissions());

        pengguna.buatPeran(tenantId, nama, keterangan(minta.description()), String.join(",", daftar));
    }

    /**
     * Ubah nama, keterangan, atau izin sebuah peran.
     *
     * <p>Ganti nama ikut mengganti peran setiap penggunanya, dalam transaksi
     * yang sama: pengguna menyimpan NAMA perannya, dan pengguna yang perannya
     * tidak lagi ada tidak bisa apa-apa.
     */
    @Transactional
    public void ubahPeran(ForgeHubPrincipal p, String namaLama, Permintaan.Peran minta) {
        UUID tenantId = p.tenantId();
        Map<String, Object> lama = pengguna.peranSatu(tenantId, namaLama);

        if (lama == null) throw ApiException.tidakAda("Peran tidak ada.");

        String asli = (String) lama.get("name");

        if (Izin.ADMINISTRATOR.equals(asli)) {
            throw ApiException.salah("Peran Administrator tidak bisa diubah.");
        }

        // Yang sedang memegang izin lebih sempit tidak boleh menyunting peran
        // yang lebih luas — bahkan hanya untuk menyempitkannya.
        pastikanTercakup(p, tenantId, asli);

        String nama = minta.name() == null ? asli : namaPeran(minta.name());
        Map<String, Object> bentrok = nama.equalsIgnoreCase(asli) ? null : pengguna.peranSatu(tenantId, nama);

        if (bentrok != null) throw ApiException.sudahAda("Peran '" + nama + "' sudah ada.");

        List<String> daftar = minta.permissions() == null
                ? List.copyOf(Izin.urai((String) lama.get("permissions")))
                : izinBaru(p, minta.permissions());

        pengguna.ubahPeran(tenantId, asli, nama, keterangan(minta.description()), String.join(",", daftar));

        if (!nama.equals(asli)) pengguna.gantiNamaPeranPengguna(tenantId, asli, nama);

        izin.lupakan();
    }

    /** Peran yang masih dipakai tidak bisa dihapus: penggunanya akan tertinggal tanpa izin apa pun. */
    @Transactional
    public void hapusPeran(ForgeHubPrincipal p, String nama) {
        UUID tenantId = p.tenantId();
        Map<String, Object> peran = pengguna.peranSatu(tenantId, nama);

        if (peran == null) throw ApiException.tidakAda("Peran tidak ada.");

        String asli = (String) peran.get("name");

        if (Izin.ADMINISTRATOR.equals(asli)) {
            throw ApiException.salah("Peran Administrator tidak bisa dihapus.");
        }

        pastikanTercakup(p, tenantId, asli);

        long dipakai = pengguna.jumlahPemakaiPeran(tenantId, asli);

        if (dipakai > 0) {
            throw ApiException.sudahAda("Peran '" + asli + "' masih dipakai " + dipakai
                    + " pengguna. Ganti peran mereka dulu.");
        }

        pengguna.hapusPeran(tenantId, asli);
        izin.lupakan();
    }

    // -----------------------------------------------------------------
    // Lisensi dan setelan
    // -----------------------------------------------------------------

    /**
     * Lisensi.
     *
     * <p>"Terpakai" dihitung dari robot yang benar-benar ada, bukan dari angka
     * yang pernah dituliskan seseorang ke kolom {@code used}. Angka yang
     * disimpan akan menyimpang begitu satu robot dihapus tanpa lewat layar ini.
     */
    public List<Map<String, Object>> lisensi(UUID tenantId) {
        List<Map<String, Object>> baris = pengguna.lisensi(tenantId);

        long attended = robots.hitungTipe(tenantId, true);
        long unattended = robots.hitungTipe(tenantId, false);

        for (Map<String, Object> b : baris) {
            String produk = String.valueOf(b.get("product"));

            b.put("used", produk.contains("Attended") && !produk.contains("Unattended")
                    ? attended : unattended);
        }

        return baris;
    }

    public Map<String, Object> setelan(ForgeHubPrincipal p) {
        Map<String, Object> hasil = new LinkedHashMap<>();

        hasil.put("tenant", pengguna.namaTenant(p.tenantId()));
        hasil.put("serverTime", Db.nowText());
        hasil.put("displayTimezone", zonaTampilan);
        hasil.put("robotOfflineAfterSeconds", RobotRepository.PUTUS_SETELAH_DETIK);
        hasil.put("tokenLifetimeHours", jwt.getExpirationMinutes() / 60);

        // "database", bukan "dataDirectory": datanya ada di PostgreSQL, bukan di
        // sebuah folder. Menyisakan nama medan yang lama akan membuat layar
        // Setelan menampilkan jalur folder yang tidak berarti apa-apa.
        hasil.put("database", "PostgreSQL");
        hasil.put("counts", ringkasan.isiBasisData(p.tenantId()));

        return hasil;
    }

    // -----------------------------------------------------------------
    // Alat
    // -----------------------------------------------------------------

    /** Nama resmi peran yang diminta — harus ada, dan tidak lebih luas dari peran pemintanya. */
    private String peranSah(ForgeHubPrincipal p, UUID tenantId, String diminta) {
        Map<String, Object> peran = pengguna.peranSatu(tenantId, diminta.trim());

        if (peran == null) throw ApiException.salah("Peran '" + diminta.trim() + "' tidak ada.");

        String nama = (String) peran.get("name");
        pastikanTercakup(p, tenantId, nama);

        return nama;
    }

    /**
     * Peran itu tidak memberi izin yang tidak dimiliki peran pemintanya.
     * Administrator berarti semuanya, apa pun isi barisnya (lihat Izin).
     */
    private void pastikanTercakup(ForgeHubPrincipal p, UUID tenantId, String namaPeran) {
        if (namaPeran == null) return;

        Set<String> target;

        if (Izin.ADMINISTRATOR.equals(namaPeran)) {
            target = Set.of("*");
        } else {
            Map<String, Object> peran = pengguna.peranSatu(tenantId, namaPeran);
            target = Izin.urai(peran == null ? null : (String) peran.get("permissions"));
        }

        String kurang = Izin.takTercakup(izin.pola(p), target);

        if (kurang != null) {
            throw ApiException.tidakBerhak("Peran '" + namaPeran + "' memuat izin '" + kurang
                    + "' yang tidak dimiliki peran Anda.");
        }
    }

    /** Izin dari layar Peran: dikenal, dirapikan, dan tidak lebih luas dari milik pemintanya. */
    private List<String> izinBaru(ForgeHubPrincipal p, List<String> diminta) {
        List<String> daftar = Izin.rapikan(diminta);
        String kurang = Izin.takTercakup(izin.pola(p), daftar);

        if (kurang != null) {
            throw ApiException.tidakBerhak("Anda tidak bisa memberi izin '" + kurang
                    + "' yang tidak dimiliki peran Anda sendiri.");
        }

        return daftar;
    }

    private static String namaPeran(String nama) {
        if (nama == null || nama.isBlank()) throw ApiException.salah("Nama peran wajib diisi.");

        String bersih = nama.trim();

        if (bersih.length() > PANJANG_NAMA_PERAN) {
            throw ApiException.salah("Nama peran paling panjang " + PANJANG_NAMA_PERAN + " karakter.");
        }

        if (bersih.equalsIgnoreCase(Izin.ADMINISTRATOR)) {
            throw ApiException.sudahAda("Peran 'Administrator' sudah ada.");
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
}
