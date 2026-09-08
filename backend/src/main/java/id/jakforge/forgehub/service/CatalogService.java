package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.model.Severity;
import id.jakforge.forgehub.repository.CatalogRepository;
import id.jakforge.forgehub.repository.LogRepository;
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

    public List<Map<String, Object>> proses(UUID tenantId) {
        return katalog.proses(tenantId);
    }

    /**
     * Nama yang sudah ada DIPERBARUI, bukan ditolak.
     *
     * <p>Menerbitkan ulang versi yang lebih baru adalah hal yang paling sering
     * dilakukan, dan menolaknya sebagai "sudah ada" memaksa orang menghapus
     * dulu — yang berarti sesaat prosesnya tidak ada sama sekali.
     */
    public void simpanProses(UUID tenantId, Permintaan.Proses minta) {
        if (minta.name() == null) throw ApiException.salah("Nama proses wajib diisi.");

        if (katalog.adaProses(tenantId, minta.name())) {
            katalog.perbaruiProses(tenantId, minta.name(), minta.packageName(),
                    minta.packageVersion(), minta.environment(), minta.description());
        } else {
            katalog.buatProses(tenantId, minta.name(), minta.packageName(), minta.packageVersion(),
                    minta.environment() == null ? "Production" : minta.environment(),
                    minta.description());
        }
    }

    public void hapusProses(UUID tenantId, String nama) {
        if (katalog.hapusProses(tenantId, nama) == 0) {
            throw ApiException.tidakAda("Proses '" + nama + "' tidak ada.");
        }
    }

    // -----------------------------------------------------------------
    // Paket
    // -----------------------------------------------------------------

    public List<Map<String, Object>> paket(UUID tenantId) {
        return katalog.paket(tenantId);
    }

    @Transactional
    public Map<String, Object> terbitkan(UUID tenantId, String penerbit, Permintaan.Paket minta) {
        if (minta.name() == null) throw ApiException.salah("Nama paket wajib diisi.");

        byte[] isi = uraiIsi(minta.contentBase64());
        long ukuran = isi == null ? 0 : isi.length;

        if (katalog.adaPaket(tenantId, minta.name(), minta.version())) {
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
            katalog.buatProses(tenantId, minta.name(), minta.name(), minta.version(),
                    minta.environment(), minta.description());
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
