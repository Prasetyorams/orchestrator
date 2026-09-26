package id.jakforge.forgehub.security;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Izin peran: apa yang boleh dilakukan seseorang.
 *
 * <p>Izin berbentuk {@code sumber.tindakan} — "processes.read", "jobs.create".
 * Peran menyimpan POLA, dipisah koma, di kolom {@code roles.permissions}:
 *
 * <ul>
 *   <li>{@code *} — semuanya (Administrator);</li>
 *   <li>{@code processes.*} — semua tindakan atas proses;</li>
 *   <li>{@code *.read} — membaca apa pun (Auditor);</li>
 *   <li>{@code processes.read} — satu tindakan.</li>
 * </ul>
 *
 * <p>Izin dibaca dari peran orang itu SAAT INI di basis data, bukan dari peran
 * yang tertulis di tokennya: token berlaku berjam-jam, dan peran yang dicabut
 * harus berhenti berlaku sekarang, bukan besok pagi. Supaya denyut robot tiap
 * beberapa detik tidak menjadi kueri tiap beberapa detik, hasilnya diingat
 * {@value #SEGAR_MS} milidetik per orang, dan dilupakan seketika begitu peran
 * atau pengguna diubah lewat ForgeHub.
 *
 * <p>Peran Administrator selalu berarti semuanya, apa pun isi barisnya — dan
 * barisnya tidak bisa diubah atau dihapus. Tanpa itu, satu penyuntingan yang
 * keliru bisa mengunci semua orang di luar ForgeHub, tanpa ada yang tersisa
 * untuk membukanya lagi.
 */
@Component
public class Izin implements PemeriksaIzin {

    public static final String ADMINISTRATOR = "Administrator";

    /** Satu baris matriks izin: sumber dan tindakan yang berlaku untuknya. */
    public record Sumber(String kunci, List<String> aksi) {
    }

    private static final List<String> CRUD = List.of("read", "create", "update", "delete");

    /**
     * Semua izin yang dikenal, dalam urutan baris matriks di layar Peran.
     *
     * <p>Tindakan yang tidak punya endpoint tidak ditawarkan: mesin dan
     * lingkungan hanya bisa dibuat dan dihapus, jejak audit hanya dibaca.
     * Kotak centang yang tidak mengubah apa pun hanya menyesatkan orang yang
     * menyusun perannya.
     *
     * <p>{@code robots.update} adalah izin DENYUT robot: akun yang dipakai
     * JakRunner memerlukannya, bersama jobs.update, logs.create, assets.read,
     * dan queues.update — lihat peran Robot di V6.
     */
    public static final List<Sumber> KATALOG = List.of(
            new Sumber("processes", CRUD),
            new Sumber("jobs", CRUD),
            new Sumber("triggers", CRUD),
            new Sumber("packages", CRUD),
            new Sumber("queues", CRUD),
            new Sumber("assets", CRUD),
            new Sumber("buckets", CRUD),
            new Sumber("robots", CRUD),
            new Sumber("logs", List.of("read", "create", "delete")),
            new Sumber("folders", CRUD),
            new Sumber("machines", List.of("read", "create", "delete")),
            new Sumber("environments", List.of("read", "create", "delete")),
            new Sumber("users", CRUD),
            new Sumber("roles", CRUD),
            new Sumber("alerts", List.of("read", "update")),
            new Sumber("audit", List.of("read")),
            new Sumber("settings", List.of("read")));

    static final long SEGAR_MS = 10_000;

    private record Tersimpan(Set<String> pola, long sampai) {
    }

    private final UserRepository pengguna;
    private final Map<UUID, Tersimpan> simpanan = new ConcurrentHashMap<>();

    public Izin(UserRepository pengguna) {
        this.pengguna = pengguna;
    }

    // -----------------------------------------------------------------
    // Izin seseorang
    // -----------------------------------------------------------------

    /** Pola izin peran orang itu saat ini; kosong untuk pengguna nonaktif atau yang sudah dihapus. */
    public Set<String> pola(ForgeHubPrincipal p) {
        long kini = System.currentTimeMillis();
        Tersimpan ada = simpanan.get(p.userId());

        if (ada != null && ada.sampai() > kini) return ada.pola();

        Set<String> pola = muat(p);
        simpanan.put(p.userId(), new Tersimpan(pola, kini + SEGAR_MS));

        return pola;
    }

    private Set<String> muat(ForgeHubPrincipal p) {
        Map<String, Object> baris = pengguna.izinPengguna(p.userId(), p.tenantId());

        // Pengguna yang dinonaktifkan tidak kehilangan tokennya — token tidak
        // bisa ditarik kembali — tapi kehilangan semua izinnya sekarang juga.
        if (baris == null || !Boolean.TRUE.equals(baris.get("isActive"))) return Set.of();

        if (ADMINISTRATOR.equals(baris.get("role"))) return Set.of("*");

        return urai((String) baris.get("permissions"));
    }

    @Override
    public boolean boleh(ForgeHubPrincipal p, String izin) {
        return cocok(pola(p), izin);
    }

    public void perlu(ForgeHubPrincipal p, String izin) {
        if (!boleh(p, izin)) throw tolak(izin);
    }

    /** Penjaga untuk layanan yang baru tahu di tengah jalan izin mana yang diperlukan. */
    public Penjaga penjaga(ForgeHubPrincipal p) {
        return izin -> perlu(p, izin);
    }

    /** Sesudah peran atau pengguna berubah: yang tersimpan tidak boleh berlaku sepuluh detik lagi. */
    public void lupakan() {
        simpanan.clear();
    }

    public static ApiException tolak(String izin) {
        return ApiException.tidakBerhak("Peran Anda tidak punya izin '" + izin + "'.");
    }

    // -----------------------------------------------------------------
    // Pola
    // -----------------------------------------------------------------

    public static boolean cocok(Collection<String> pola, String izin) {
        int titik = izin.indexOf('.');
        String sumber = izin.substring(0, titik);
        String aksi = izin.substring(titik + 1);

        for (String p : pola) {
            if (p.equals("*") || p.equals(izin) || p.equals(sumber + ".*") || p.equals("*." + aksi)) return true;
        }

        return false;
    }

    /** "a, b,c" menjadi {a, b, c}; kosong dan null menjadi himpunan kosong. */
    public static Set<String> urai(String teks) {
        Set<String> hasil = new LinkedHashSet<>();
        if (teks == null) return hasil;

        for (String bagian : teks.split(",")) {
            String x = bagian.trim();
            if (!x.isEmpty()) hasil.add(x);
        }

        return hasil;
    }

    /** Setiap izin di katalog yang dicakup pola itu. */
    public static Set<String> jabarkan(Collection<String> pola) {
        Set<String> hasil = new LinkedHashSet<>();

        for (Sumber s : KATALOG) {
            for (String a : s.aksi()) {
                String izin = s.kunci() + "." + a;
                if (cocok(pola, izin)) hasil.add(izin);
            }
        }

        return hasil;
    }

    /**
     * Izin dari layar Peran, dalam bentuk yang disimpan: urutan katalog, dan
     * {@code sumber.*} untuk sumber yang semua tindakannya dipilih — supaya
     * dua peran yang sama tertulis sama, dan tindakan baru di masa depan ikut
     * terbawa ke peran yang memang sudah memegang seluruh sumber itu.
     *
     * <p>Yang tidak dikenal DITOLAK, bukan dibuang diam-diam: salah ketik di
     * "procesess.read" berarti peran yang disimpan tidak bisa apa-apa, dan
     * penyusunnya tidak akan pernah tahu sebabnya.
     */
    public static List<String> rapikan(Collection<String> masukan) {
        Set<String> dipilih = new LinkedHashSet<>();

        for (String x : masukan) {
            String izin = x == null ? "" : x.trim();
            if (izin.isEmpty()) continue;

            if (!dikenal(izin)) throw ApiException.salah("Izin tidak dikenal: '" + izin + "'.");

            dipilih.addAll(jabarkan(List.of(izin)));
        }

        List<String> hasil = new ArrayList<>();

        for (Sumber s : KATALOG) {
            List<String> aksi = s.aksi().stream().filter(a -> dipilih.contains(s.kunci() + "." + a)).toList();

            if (aksi.size() == s.aksi().size()) {
                hasil.add(s.kunci() + ".*");
            } else {
                for (String a : aksi) hasil.add(s.kunci() + "." + a);
            }
        }

        return hasil;
    }

    /** "processes.read" atau "processes.*", dengan sumber dan tindakan dari katalog. */
    static boolean dikenal(String izin) {
        int titik = izin.indexOf('.');
        if (titik <= 0) return false;

        String sumber = izin.substring(0, titik);
        String aksi = izin.substring(titik + 1);

        for (Sumber s : KATALOG) {
            if (s.kunci().equals(sumber)) return aksi.equals("*") || s.aksi().contains(aksi);
        }

        return false;
    }

    /**
     * Izin pertama dari {@code target} yang TIDAK dicakup {@code pemberi}, atau
     * null kalau semuanya tercakup.
     *
     * <p>Dipakai supaya tidak ada yang bisa memberi — lewat peran baru, atau
     * dengan memasang peran pada seseorang — izin yang ia sendiri tidak punya.
     * Tanpa itu, siapa pun yang boleh menyunting peran bisa menjadikan dirinya
     * Administrator dalam dua klik.
     */
    public static String takTercakup(Collection<String> pemberi, Collection<String> target) {
        for (String izin : jabarkan(target)) {
            if (!cocok(pemberi, izin)) return izin;
        }

        return null;
    }
}
