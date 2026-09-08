package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.DashboardRepository;
import id.jakforge.forgehub.repository.RobotRepository;
import id.jakforge.forgehub.repository.UserRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.JwtService;
import id.jakforge.forgehub.security.Passwords;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pengguna, peran, penyewa, lisensi, dan setelan. */
@Service
public class AdminService {

    private final UserRepository pengguna;
    private final RobotRepository robots;
    private final DashboardRepository ringkasan;
    private final JwtService jwt;
    private final String zonaTampilan;

    public AdminService(UserRepository pengguna, RobotRepository robots,
                        DashboardRepository ringkasan, JwtService jwt,
                        @Value("${forgehub.display-timezone:UTC}") String zonaTampilan) {
        this.pengguna = pengguna;
        this.robots = robots;
        this.ringkasan = ringkasan;
        this.jwt = jwt;
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
     * <p>Pembatasan peran diperiksa DI SINI, bukan diserahkan ke antarmuka:
     * tombol yang disembunyikan tetap bisa dilewati siapa pun yang memanggil
     * API-nya langsung.
     */
    @Transactional
    public Map<String, Object> simpanPengguna(ForgeHubPrincipal p, Permintaan.Pengguna minta) {
        pastikanAdministrator(p, "Hanya Administrator yang boleh mengubah pengguna.");

        if (minta.username() == null) throw ApiException.salah("Nama pengguna wajib diisi.");

        UUID tenantId = p.tenantId();
        boolean sudahAda = pengguna.ada(tenantId, minta.username());

        if (sudahAda) {
            pengguna.perbarui(tenantId, minta.username(), minta.displayName(),
                    minta.email(), minta.role(), minta.isActive());

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
                    minta.email(),
                    minta.role() == null ? "Automation User" : minta.role(),
                    minta.isActive());
        }

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("created", !sudahAda);

        return hasil;
    }

    @Transactional
    public void hapusPengguna(ForgeHubPrincipal p, String username) {
        pastikanAdministrator(p, "Hanya Administrator yang boleh menghapus pengguna.");

        // Menghapus diri sendiri akan mengunci orangnya keluar dari ForgeHub
        // miliknya sendiri, dan tidak ada jalan masuk lain untuk membatalkannya.
        if (username.equalsIgnoreCase(p.username())) {
            throw ApiException.salah("Tidak bisa menghapus akun yang sedang dipakai.");
        }

        UUID tenantId = p.tenantId();

        // Penyewa tanpa satu pun administrator aktif tidak bisa diurus lagi:
        // tidak ada yang bisa membuat administrator baru, dan tidak ada pintu
        // belakang untuk memperbaikinya.
        if (pengguna.administrator(tenantId, username)
                && pengguna.jumlahAdministratorAktif(tenantId) <= 1) {

            throw ApiException.salah("Ini satu-satunya Administrator yang tersisa.");
        }

        if (pengguna.hapus(tenantId, username) == 0) {
            throw ApiException.tidakAda("Pengguna tidak ada.");
        }
    }

    public List<Map<String, Object>> peran(UUID tenantId) {
        return pengguna.peran(tenantId);
    }

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

    private static void pastikanAdministrator(ForgeHubPrincipal p, String pesan) {
        if (!"Administrator".equals(p.role())) throw ApiException.tidakBerhak(pesan);
    }
}
