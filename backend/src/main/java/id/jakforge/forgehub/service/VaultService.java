package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.model.AssetType;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.VaultRepository;
import id.jakforge.forgehub.security.SecretBox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aturan tentang aset, kredensial, dan gudang berkas.
 *
 * <p>Penyandian ada DI SINI, bukan di repository. Lapisan data yang memegang
 * kuncinya berarti setiap kueri berpotensi mengembalikan nilai polos, dan
 * satu-satunya yang mencegahnya adalah kedisiplinan penulis kuerinya.
 */
@Service
public class VaultService {

    /** Sama alasannya dengan batas paket: isinya lewat base64 di dalam JSON. */
    private static final long MAKS_BERKAS_BITA = 32L * 1024 * 1024;

    private final VaultRepository gudang;
    private final SecretBox rahasia;

    public VaultService(VaultRepository gudang, SecretBox rahasia) {
        this.gudang = gudang;
        this.rahasia = rahasia;
    }

    // -----------------------------------------------------------------
    // Aset
    // -----------------------------------------------------------------

    public List<Map<String, Object>> aset(UUID tenantId) {
        return gudang.aset(tenantId);
    }

    public Map<String, Object> simpanAset(UUID tenantId, Permintaan.Aset minta) {
        if (minta.name() == null) throw ApiException.salah("Nama aset wajib diisi.");

        AssetType tipe = AssetType.dari(minta.type());

        if (tipe == null) {
            throw ApiException.salah("Tipe aset tidak dikenal: '" + minta.type() + "'.");
        }

        periksaNilai(tipe, minta.value());

        String tipeLama = gudang.tipeAset(tenantId, minta.name());

        // Nama pengguna hanya milik Credential. Aset yang berganti tipe dari
        // Credential ke Text tidak boleh membawa nama pengguna yang tidak
        // lagi ditampilkan di mana pun.
        String pengguna = tipe == AssetType.Credential ? minta.username() : null;

        tulisAset(tenantId, minta.name(), tipe, pengguna, minta.value(),
                minta.description(), minta.scope(), tipeLama);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("created", tipeLama == null);

        return hasil;
    }

    /**
     * Aset rahasia dibuka di sini SAJA.
     *
     * <p>Untuk Credential, {@code value} adalah kata sandinya — sama seperti
     * sebelum kredensial menjadi aset, jadi Get Asset yang sudah membaca aset
     * Credential tetap menerima hal yang sama — dan {@code username} ikut.
     */
    public Map<String, Object> nilaiAset(UUID tenantId, String nama) {
        Map<String, Object> baris = gudang.nilaiAset(tenantId, nama);

        if (baris == null) throw ApiException.tidakAda("Aset '" + nama + "' tidak ada.");

        AssetType tipe = AssetType.dari((String) baris.get("type"));
        String mentah = (String) baris.get("valueText");

        // HashMap, bukan Map.of: nilainya boleh null, dan Map.of melempar
        // NullPointerException untuk nilai null. Aset yang ada tapi kosong
        // adalah keadaan yang sah.
        Map<String, Object> hasil = new HashMap<>();
        hasil.put("name", nama);
        hasil.put("type", baris.get("type"));
        hasil.put("value", tipe != null && tipe.rahasia() ? rahasia.unprotect(mentah) : mentah);

        if (tipe == AssetType.Credential) hasil.put("username", baris.get("username"));

        return hasil;
    }

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
    private void tulisAset(UUID tenantId, String nama, AssetType tipe, String pengguna, String nilai,
                           String keterangan, String cakupan, String tipeLama) {

        String tersimpan = tipe.rahasia() ? rahasia.protect(nilai) : nilai;

        if (tipeLama == null) {
            gudang.buatAset(tenantId, nama, tipe.name(), pengguna, tersimpan, keterangan, cakupan);
            return;
        }

        boolean pertahankan = tipe.rahasia()
                && (nilai == null || nilai.isEmpty())
                && tipe == AssetType.dari(tipeLama);

        gudang.perbaruiAset(tenantId, nama, tipe.name(), pengguna, tersimpan, pertahankan,
                keterangan, cakupan);
    }

    public void hapusAset(UUID tenantId, String nama) {
        if (gudang.hapusAset(tenantId, nama) == 0) {
            throw ApiException.tidakAda("Aset tidak ada.");
        }
    }

    /**
     * Nilai bertipe angka dan boolean diperiksa DI SINI, bukan dibiarkan
     * meledak nanti di dalam robot yang sedang berjalan. Kegagalan di sini
     * dilihat orang yang baru saja mengetiknya; kegagalan di sana dilihat
     * sebagai automasi yang berhenti di tengah malam.
     */
    private static void periksaNilai(AssetType tipe, String nilai) {
        if (nilai == null || nilai.isEmpty()) return;

        if (tipe == AssetType.Integer) {
            try {
                Long.parseLong(nilai.trim());
            } catch (NumberFormatException e) {
                throw ApiException.salah("Aset bertipe Integer harus berisi bilangan bulat.");
            }
        }

        if (tipe == AssetType.Bool) {
            String v = nilai.trim();

            if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                throw ApiException.salah("Aset bertipe Bool harus berisi true atau false.");
            }
        }
    }

    // -----------------------------------------------------------------
    // Kredensial
    //
    // Sejak V3 kredensial adalah aset bertipe Credential. Jalur di bawah ini
    // tetap ada untuk activity Get Credential dan klien lama; semuanya
    // bekerja pada aset yang sama dengan yang tampil di halaman Aset.
    // -----------------------------------------------------------------

    public List<Map<String, Object>> kredensial(UUID tenantId) {
        return gudang.kredensial(tenantId);
    }

    public void simpanKredensial(UUID tenantId, Permintaan.Kredensial minta) {
        if (minta.name() == null) throw ApiException.salah("Nama kredensial wajib diisi.");

        String tipeLama = gudang.tipeAset(tenantId, minta.name());

        // Dulu kredensial dan aset punya daftar nama sendiri-sendiri; sekarang
        // satu. Menyimpan kredensial di atas aset Text bernama sama akan
        // menimpa nilai yang dipakai proses lain tanpa ada yang memintanya.
        if (tipeLama != null && AssetType.dari(tipeLama) != AssetType.Credential) {
            throw ApiException.sudahAda(
                    "Nama '" + minta.name() + "' sudah dipakai aset bertipe " + tipeLama + ".");
        }

        tulisAset(tenantId, minta.name(), AssetType.Credential, minta.username(), minta.password(),
                minta.description(), "Global", tipeLama);
    }

    public Map<String, Object> nilaiKredensial(UUID tenantId, String nama) {
        Map<String, Object> baris = gudang.nilaiKredensial(tenantId, nama);

        if (baris == null) throw ApiException.tidakAda("Kredensial tidak ada.");

        Map<String, Object> hasil = new HashMap<>();
        hasil.put("username", baris.get("username"));
        hasil.put("password", rahasia.unprotect((String) baris.get("passwordEnc")));

        return hasil;
    }

    public void hapusKredensial(UUID tenantId, String nama) {
        if (gudang.hapusKredensial(tenantId, nama) == 0) {
            throw ApiException.tidakAda("Kredensial tidak ada.");
        }
    }

    // -----------------------------------------------------------------
    // Gudang berkas
    // -----------------------------------------------------------------

    public List<Map<String, Object>> daftarGudang(UUID tenantId) {
        return gudang.gudang(tenantId);
    }

    public void buatGudang(UUID tenantId, Permintaan.Bernama minta) {
        if (minta.name() == null) throw ApiException.salah("Nama gudang wajib diisi.");

        if (gudang.adaGudang(tenantId, minta.name())) {
            throw ApiException.sudahAda("Gudang '" + minta.name() + "' sudah ada.");
        }

        gudang.buatGudang(tenantId, minta.name(), minta.description());
    }

    @Transactional
    public void hapusGudang(UUID tenantId, String nama) {
        if (gudang.hapusGudang(tenantId, nama) == 0) {
            throw ApiException.tidakAda("Gudang tidak ada.");
        }
    }

    public List<Map<String, Object>> berkas(UUID tenantId, String nama) {
        return gudang.berkas(tenantId, nama);
    }

    @Transactional
    public Map<String, Object> unggah(UUID tenantId, String namaGudang, String pengunggah,
                                      Permintaan.Berkas minta) {

        String namaBerkas = bersihkanNama(minta.fileName());

        if (namaBerkas == null) throw ApiException.salah("fileName wajib diisi dan sah.");

        byte[] isi;
        try {
            isi = Base64.getDecoder().decode(minta.contentBase64());
        } catch (IllegalArgumentException e) {
            throw ApiException.salah("contentBase64 bukan base64 yang sah.");
        }

        if (isi.length > MAKS_BERKAS_BITA) {
            throw ApiException.salah(
                    "Berkas terlalu besar. Batasnya " + (MAKS_BERKAS_BITA / 1024 / 1024) + " MB.");
        }

        if (!gudang.adaGudang(tenantId, namaGudang)) {
            throw ApiException.tidakAda("Gudang '" + namaGudang + "' tidak ada.");
        }

        // Berkas dengan nama yang sama DIGANTI, bukan ditumpuk. Gudang berisi
        // lima "laporan.xlsx" dengan waktu unggah berbeda tidak menolong siapa
        // pun yang mencari laporan hari ini.
        gudang.hapusBerkasBernama(tenantId, namaGudang, namaBerkas);
        gudang.simpanBerkas(tenantId, namaGudang, namaBerkas, minta.contentType(),
                isi.length, pengunggah, isi);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("fileName", namaBerkas);
        hasil.put("sizeBytes", isi.length);

        return hasil;
    }

    public Map<String, Object> isiBerkas(UUID tenantId, String namaGudang, String id) {
        UUID berkasId = Db.uuid(id);

        Map<String, Object> berkas = berkasId == null
                ? null : gudang.isiBerkas(tenantId, namaGudang, berkasId);

        if (berkas == null || ((byte[]) berkas.get("content")).length == 0) {
            throw ApiException.tidakAda("Berkas tidak ada.");
        }

        return berkas;
    }

    public void hapusBerkas(UUID tenantId, String namaGudang, String id) {
        UUID berkasId = Db.uuid(id);

        if (berkasId == null || gudang.hapusBerkas(tenantId, namaGudang, berkasId) == 0) {
            throw ApiException.tidakAda("Berkas tidak ada.");
        }
    }

    /**
     * Buang segala yang bisa mengubah tempat berkas mendarat.
     *
     * <p>Nama berkas datang dari luar dan dipakai sebagai nama unduhan. Pemisah
     * jalur dibuang supaya tidak ada yang bisa menulis {@code ../} ke dalamnya
     * dan menaruh berkas di tempat lain pada komputer orang yang mengunduhnya.
     */
    static String bersihkanNama(String nama) {
        if (nama == null) return null;

        String bersih = nama.replace('\\', '/');
        int garis = bersih.lastIndexOf('/');

        if (garis >= 0) bersih = bersih.substring(garis + 1);

        bersih = bersih.trim();

        // "." dan ".." tidak menyisakan apa pun sesudah pemisahnya dibuang, dan
        // keduanya bukan nama berkas.
        if (bersih.isEmpty() || ".".equals(bersih) || "..".equals(bersih)) return null;

        return bersih;
    }
}
