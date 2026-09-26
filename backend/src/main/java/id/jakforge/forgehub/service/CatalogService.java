package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.model.Severity;
import id.jakforge.forgehub.repository.CatalogRepository;
import id.jakforge.forgehub.repository.LogRepository;
import id.jakforge.forgehub.repository.Tempat;
import id.jakforge.forgehub.security.Penjaga;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Aturan tentang proses dan paket. */
@Service
public class CatalogService {

    /**
     * Batas ukuran paket.
     *
     * <p>Isinya dikirim sebagai base64 DI DALAM badan JSON: paket 64 MB menjadi
     * sekitar 85 MB teks yang harus muat di memori sekaligus. Tanpa batas, satu
     * penerbitan yang keliru menjatuhkan layanan untuk semua orang.
     */
    private static final long MAKS_PAKET_BITA = 64L * 1024 * 1024;

    private final CatalogRepository katalog;
    private final LogRepository catatan;

    public CatalogService(CatalogRepository katalog, LogRepository catatan) {
        this.katalog = katalog;
        this.catatan = catatan;
    }

    // -----------------------------------------------------------------
    // Proses
    // -----------------------------------------------------------------

    /** @param folderId null berarti seluruh penyewa, seperti sebelum folder ada. */
    public List<Map<String, Object>> proses(UUID tenantId, UUID folderId) {
        return katalog.proses(tenantId, folderId);
    }

    /**
     * Folder proses yang dimaksud sebuah permintaan, atau null kalau proses
     * itu tidak ada (di folder yang disebut). Aturannya di {@link #pilihFolder}.
     */
    public UUID folderProses(UUID tenantId, String nama, UUID folderId) {
        return pilihFolder(katalog.tempatProses(tenantId, nama), folderId,
                "Proses '" + nama + "' ada di beberapa folder. Sebutkan foldernya.");
    }

    /**
     * Folder mana yang dimaksud oleh permintaan yang menyebut sebuah NAMA.
     *
     * <p>Nama proses dan pemicu unik per folder, bukan per penyewa (V5), jadi
     * satu nama bisa ada di beberapa folder:
     *
     * <ul>
     *   <li>Permintaan yang menyebut folder — dasbor selalu menyebutnya —
     *       mendapat yang di folder itu, atau null.</li>
     *   <li>Permintaan tanpa folder — Studio (Start Job) dan pemanggil lama —
     *       mendapat satu-satunya yang bernama itu. Kalau ada beberapa, yang
     *       di folder BAWAAN, karena ke sanalah penerbitan dari Studio
     *       memasangnya. Kalau tidak ada satu pun di folder bawaan, DITOLAK:
     *       menebak berarti menjalankan proses di folder yang tidak dimaksud,
     *       dengan robot folder itu.</li>
     * </ul>
     *
     * @param tempat     urutan dari repositori: folder bawaan lebih dulu
     * @param pesanGanda pesan 409 untuk nama yang tidak bisa diputuskan
     */
    public static UUID pilihFolder(List<Tempat> tempat, UUID folderId, String pesanGanda) {
        if (folderId != null) {
            for (Tempat t : tempat) {
                if (folderId.equals(t.folderId())) return folderId;
            }

            return null;
        }

        if (tempat.isEmpty()) return null;
        if (tempat.size() == 1) return tempat.get(0).folderId();

        for (Tempat t : tempat) {
            if (t.bawaan()) return t.folderId();
        }

        throw ApiException.sudahAda(pesanGanda);
    }

    /**
     * Nama yang sudah ada DI FOLDER ITU diperbarui, bukan ditolak.
     *
     * <p>Menerbitkan ulang versi yang lebih baru adalah hal yang paling sering
     * dilakukan, dan menolaknya sebagai "sudah ada" memaksa orang menghapus
     * dulu — yang berarti sesaat prosesnya tidak ada sama sekali.
     *
     * <p>Nama yang sama di folder LAIN tidak disentuh dan tidak menghalangi:
     * itu proses lain, dengan versi, pemicu, dan riwayatnya sendiri — seperti
     * paket yang sama dipasang di dua folder Orchestrator.
     *
     * @param folderId folder prosesnya. null — bentuk yang dikirim pemanggil
     *                 lama — berarti proses bernama itu di mana pun ia berada
     *                 (lihat {@link #pilihFolder}), atau folder bawaan untuk
     *                 yang baru.
     * @param penjaga  processes.create untuk yang baru, processes.update untuk
     *                 yang sudah ada — baru ketahuan di sini
     */
    public void simpanProses(UUID tenantId, Permintaan.Proses minta, UUID folderId, Penjaga penjaga) {
        if (minta.name() == null) throw ApiException.salah("Nama proses wajib diisi.");

        // Dari dasbor, paket dan versinya dipilih dari daftar — yang tidak ada
        // berarti daftarnya sudah basi, dan proses yang menunjuk paket hilang
        // baru ketahuan saat robot gagal menjalankannya.
        if (folderId != null && minta.packageName() != null && minta.packageVersion() != null
                && !katalog.adaPaket(tenantId, minta.packageName(), minta.packageVersion())) {
            throw ApiException.salah("Paket '" + minta.packageName() + "' versi "
                    + minta.packageVersion() + " tidak ada.");
        }

        UUID folderLama = folderProses(tenantId, minta.name(), folderId);

        penjaga.perluSimpan("processes", folderLama != null);

        if (folderLama != null) {
            katalog.perbaruiProses(tenantId, folderLama, minta.name(), minta.packageName(),
                    minta.packageVersion(), minta.environment(), minta.description());
        } else {
            katalog.buatProses(tenantId, minta.name(), minta.packageName(), minta.packageVersion(),
                    minta.environment() == null ? "Production" : minta.environment(),
                    minta.description(), folderId);
        }
    }

    /**
     * Pemicu dan riwayat pekerjaannya ikut pindah; lihat CatalogRepository.pindahProses.
     *
     * @param dari folder asal; null berarti proses bernama itu di mana pun ia berada
     * @param ke   folder tujuan
     */
    @Transactional
    public void pindahProses(UUID tenantId, String nama, UUID dari, UUID ke) {
        if (ke == null) throw ApiException.salah("folderId wajib diisi.");

        UUID asal = folderProses(tenantId, nama, dari);

        if (asal == null) throw ApiException.tidakAda("Proses '" + nama + "' tidak ada.");
        if (asal.equals(ke)) return;

        // Diperiksa lebih dulu, bukan dibiarkan ditolak indeks unik: yang itu
        // hanya menghasilkan "terjadi kesalahan di server".
        if (katalog.adaProses(tenantId, nama, ke)) {
            throw ApiException.sudahAda("Folder tujuan sudah punya proses bernama '" + nama + "'.");
        }

        String bentrok = katalog.pemicuBentrok(tenantId, nama, asal, ke);

        if (bentrok != null) {
            throw ApiException.sudahAda("Folder tujuan sudah punya pemicu bernama '" + bentrok + "'.");
        }

        katalog.pindahProses(tenantId, nama, asal, ke);
    }

    /** @param folderId null berarti proses bernama itu di mana pun ia berada. */
    public void hapusProses(UUID tenantId, String nama, UUID folderId) {
        UUID folder = folderProses(tenantId, nama, folderId);

        if (folder == null || katalog.hapusProses(tenantId, nama, folder) == 0) {
            throw ApiException.tidakAda("Proses '" + nama + "' tidak ada.");
        }
    }

    // -----------------------------------------------------------------
    // Paket
    // -----------------------------------------------------------------

    /** @param folderId null berarti seluruh umpan; selain itu paket yang dipakai proses di folder itu. */
    public List<Map<String, Object>> paket(UUID tenantId, UUID folderId) {
        return katalog.paket(tenantId, folderId);
    }

    /** @param penjaga packages.create untuk versi baru, packages.update untuk menerbitkan ulang versi yang ada */
    @Transactional
    public Map<String, Object> terbitkan(UUID tenantId, String penerbit, Permintaan.Paket minta, Penjaga penjaga) {
        if (minta.name() == null) throw ApiException.salah("Nama paket wajib diisi.");

        boolean sudahAda = katalog.adaPaket(tenantId, minta.name(), minta.version());
        penjaga.perluSimpan("packages", sudahAda);

        byte[] isi = uraiIsi(minta.contentBase64());
        long ukuran = isi == null ? 0 : isi.length;

        if (sudahAda) {
            katalog.perbaruiPaket(tenantId, minta.name(), minta.version(), minta.description(),
                    minta.entryPoint(), penerbit, ukuran, isi);
        } else {
            katalog.buatPaket(tenantId, minta.name(), minta.version(), minta.description(),
                    minta.entryPoint(), penerbit, ukuran, isi);
        }

        // Menerbitkan paket hampir selalu berarti ingin proses dengan nama yang
        // sama tersedia untuk dijalankan. Membuatnya di sini menghemat satu
        // langkah yang mudah terlupa — dan yang terlupa itu baru terasa saat
        // pekerjaan ditolak dengan "proses belum diterbitkan".
        if (katalog.adaProses(tenantId, minta.name())) {
            katalog.tautkanPaket(tenantId, minta.name(), minta.name(), minta.version());
        } else {
            // Folder bawaan: Studio belum tahu tentang folder, dan proses yang
            // baru terbit harus langsung terlihat di tempat yang sama bagi semua.
            katalog.buatProses(tenantId, minta.name(), minta.name(), minta.version(),
                    minta.environment(), minta.description(), null);
        }

        catatan.catatPeringatan(tenantId, Severity.Info, "Paket diterbitkan",
                minta.name() + " " + minta.version() + " diterbitkan oleh "
                        + (penerbit == null ? "?" : penerbit) + ".", "packages");

        catatan.tulisSistem(tenantId,
                "Paket " + minta.name() + " " + minta.version() + " diterbitkan.",
                minta.name(), null);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("name", minta.name());
        hasil.put("version", minta.version());
        hasil.put("sizeBytes", ukuran);

        return hasil;
    }

    public byte[] isiPaket(UUID tenantId, String nama, String versi) {
        byte[] isi = katalog.isiPaket(tenantId, nama, versi);

        if (isi == null) throw ApiException.tidakAda("Paket tidak ada atau tanpa isi.");

        return isi;
    }

    public void hapusPaket(UUID tenantId, String nama, String versi) {
        if (katalog.hapusPaket(tenantId, nama, versi) == 0) {
            throw ApiException.tidakAda("Paket tidak ada.");
        }
    }

    private static byte[] uraiIsi(String base64) {
        if (base64 == null || base64.isEmpty()) return null;

        byte[] isi;
        try {
            isi = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw ApiException.salah("contentBase64 bukan base64 yang sah.");
        }

        if (isi.length > MAKS_PAKET_BITA) {
            throw ApiException.salah(
                    "Paket terlalu besar. Batasnya " + (MAKS_PAKET_BITA / 1024 / 1024) + " MB.");
        }

        return isi;
    }
}
