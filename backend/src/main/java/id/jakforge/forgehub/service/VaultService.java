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

        String tersimpan = tipe.rahasia() ? rahasia.protect(minta.value()) : minta.value();
        boolean sudahAda = gudang.adaAset(tenantId, minta.name());

        if (sudahAda) {
            gudang.perbaruiAset(tenantId, minta.name(), tipe.name(), tersimpan,
                    minta.description(), minta.scope());
        } else {
            gudang.buatAset(tenantId, minta.name(), tipe.name(), tersimpan,
                    minta.description(), minta.scope());
        }

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("created", !sudahAda);

        return hasil;
    }

    /** Aset rahasia dibuka di sini SAJA. */
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

        return hasil;
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
    // -----------------------------------------------------------------

    public List<Map<String, Object>> kredensial(UUID tenantId) {
        return gudang.kredensial(tenantId);
    }

    public void simpanKredensial(UUID tenantId, Permintaan.Kredensial minta) {
        if (minta.name() == null) throw ApiException.salah("Nama kredensial wajib diisi.");

        // Kata sandi kosong menghasilkan null, bukan untai kosong tersandi.
        // Repository memakai COALESCE, jadi null berarti "biarkan yang lama".
        String tersandi = rahasia.protect(minta.password());

        if (gudang.adaKredensial(tenantId, minta.name())) {
            gudang.perbaruiKredensial(tenantId, minta.name(), minta.username(),
                    tersandi, minta.description());
        } else {
            gudang.buatKredensial(tenantId, minta.name(), minta.username(),
                    tersandi, minta.description());
        }
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
