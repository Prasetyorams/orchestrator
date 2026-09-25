package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.UserRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.JwtService;
import id.jakforge.forgehub.security.Passwords;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Masuk, siapa saya, ubah profil, dan ganti kata sandi. */
@Service
public class AuthService {

    private final UserRepository pengguna;
    private final JwtService jwt;

    public AuthService(UserRepository pengguna, JwtService jwt) {
        this.pengguna = pengguna;
        this.jwt = jwt;
    }

    @Transactional
    public Map<String, Object> masuk(Permintaan.Masuk minta) {
        if (minta.username() == null) {
            throw ApiException.salah("Nama pengguna wajib diisi.");
        }

        Map<String, Object> user = pengguna.untukMasuk(minta.username());

        // Jawaban yang SAMA untuk pengguna tak dikenal, akun nonaktif, dan kata
        // sandi salah. Jawaban yang berbeda memberi tahu penebak nama mana yang
        // benar-benar ada, dan itu memisahkan satu tebakan menjadi dua yang
        // jauh lebih murah.
        ApiException ditolak = ApiException.belumMasuk("Nama pengguna atau kata sandi salah.");

        if (user == null) throw ditolak;
        if (!Boolean.TRUE.equals(user.get("isActive"))) throw ditolak;

        String tersimpan = (String) user.get("passwordHash");

        if (!Passwords.verify(minta.password(), tersimpan)) throw ditolak;

        UUID userId = Db.uuid((String) user.get("id"));
        UUID tenantId = Db.uuid((String) user.get("tenantId"));
        String peran = (String) user.get("role");

        pengguna.catatMasuk(userId);

        // Kata sandi yang tersimpan dengan putaran lebih sedikit di-hash ulang
        // SEKARANG, saat kata sandi polosnya ada di tangan. Ini satu-satunya
        // saat itu mungkin; sesudah ini yang tersimpan hanya hash-nya.
        if (Passwords.needsRehash(tersimpan)) {
            pengguna.gantiSandi(userId, Passwords.hash(minta.password()));
        }

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("token", jwt.issue(userId, tenantId, minta.username(), peran));
        hasil.put("expiresInMinutes", jwt.getExpirationMinutes());
        hasil.put("userId", userId.toString());
        hasil.put("username", user.get("username"));
        hasil.put("displayName", user.get("displayName"));
        hasil.put("role", peran);
        hasil.put("tenantId", tenantId.toString());
        hasil.put("tenantName", user.get("tenantName"));

        return hasil;
    }

    public Map<String, Object> profil(ForgeHubPrincipal p) {
        Map<String, Object> user = pengguna.profil(p.userId(), p.tenantId());

        // Token sah untuk pengguna yang sudah tidak ada. Itu bukan 500: yang
        // salah adalah tokennya, bukan servernya.
        if (user == null) throw ApiException.belumMasuk("Pengguna sudah tidak ada.");

        return user;
    }

    /**
     * Ubah nama tampilan dan surel milik pengguna yang sedang masuk.
     *
     * <p>Mengembalikan profil yang sudah tersimpan, bukan sekadar "OK", supaya
     * yang ditampilkan dasbor sesudahnya adalah nilai yang benar-benar ada di
     * basis data — termasuk pemangkasan spasi yang dilakukan di sini.
     */
    @Transactional
    public Map<String, Object> ubahProfil(ForgeHubPrincipal p, Permintaan.Profil minta) {
        if (minta.displayName() == null) {
            throw ApiException.salah("Nama tampilan wajib diisi.");
        }

        if (minta.displayName().length() > PANJANG_NAMA) {
            throw ApiException.salah("Nama tampilan paling panjang " + PANJANG_NAMA + " karakter.");
        }

        // Surel boleh kosong — itu berarti dihapus. Kalau diisi, bentuknya
        // diperiksa di sini, bukan hanya di formulir: API ini juga bisa
        // dipanggil langsung.
        if (minta.email() != null) {
            if (minta.email().length() > PANJANG_SUREL) {
                throw ApiException.salah("Alamat surel paling panjang " + PANJANG_SUREL + " karakter.");
            }

            if (!BENTUK_SUREL.matcher(minta.email()).matches()) {
                throw ApiException.salah("Alamat surel tidak sah.");
            }
        }

        if (pengguna.ubahProfil(p.userId(), p.tenantId(), minta.displayName(), minta.email()) == 0) {
            throw ApiException.belumMasuk("Pengguna sudah tidak ada.");
        }

        return profil(p);
    }

    public Map<String, Object> gantiSandi(ForgeHubPrincipal p, Permintaan.GantiSandi minta) {
        if (minta.currentPassword() == null) {
            throw ApiException.salah("Kata sandi saat ini wajib diisi.");
        }

        if (minta.newPassword() == null || minta.newPassword().length() < 8) {
            throw ApiException.salah("Kata sandi baru minimal 8 karakter.");
        }

        // Kata sandi lama tetap diminta walau penggunanya sudah membawa token
        // yang sah. Token bisa berasal dari layar yang ditinggal terbuka; kata
        // sandi lama hanya diketahui pemiliknya.
        //
        // Salah dijawab 400, BUKAN 401. Dasbor, Studio, dan JakRunner membuang
        // tokennya begitu menerima 401 — jadi 401 di sini membuat orang yang
        // salah ketik kata sandi lamanya langsung terlempar ke layar masuk.
        // Tokennya sah; yang salah isiannya. ForgeHub .NET juga menjawab 400.
        if (!Passwords.verify(minta.currentPassword(), pengguna.hashSandi(p.userId()))) {
            throw ApiException.salah("Kata sandi saat ini salah.");
        }

        pengguna.gantiSandi(p.userId(), Passwords.hash(minta.newPassword()));

        return Map.of("status", "OK");
    }

    /** Batas panjang mengikuti kolomnya di V1__init.sql. */
    private static final int PANJANG_NAMA = 200;
    private static final int PANJANG_SUREL = 160;

    /**
     * Bentuk surel yang cukup untuk menangkap salah ketik, bukan validasi RFC.
     *
     * <p>Aturan RFC 5322 yang lengkap menerima alamat yang tidak pernah dipakai
     * orang dan hampir tidak menolak apa pun yang berguna. Yang benar-benar
     * terjadi adalah "@" yang terlupa atau spasi yang ikut tersalin.
     */
    private static final Pattern BENTUK_SUREL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
}
