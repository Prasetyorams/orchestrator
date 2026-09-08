package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.model.QueueItemStatus;
import id.jakforge.forgehub.model.Severity;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.LogRepository;
import id.jakforge.forgehub.repository.QueueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Aturan tentang antrean transaksi. */
@Service
public class QueueService {

    private static final int BATAS_BUTIR_BAWAAN = 200;
    private static final int BATAS_BUTIR_MAKS = 2000;

    private final QueueRepository antrean;
    private final LogRepository catatan;

    public QueueService(QueueRepository antrean, LogRepository catatan) {
        this.antrean = antrean;
        this.catatan = catatan;
    }

    public List<Map<String, Object>> daftar(UUID tenantId) {
        return antrean.semua(tenantId);
    }

    public void buat(UUID tenantId, Permintaan.Antrean minta) {
        if (minta.name() == null) throw ApiException.salah("Nama antrean wajib diisi.");

        if (antrean.ada(tenantId, minta.name())) {
            throw ApiException.sudahAda("Antrean '" + minta.name() + "' sudah ada.");
        }

        antrean.buat(tenantId, minta.name(), minta.description(),
                minta.maxRetries(), minta.acceptDuplicates());
    }

    @Transactional
    public void hapus(UUID tenantId, String nama) {
        // Isinya ikut dihapus. Butir yang menggantung tanpa antrean induk tidak
        // akan pernah bisa dilihat lagi lewat jalan mana pun, tapi tetap
        // terhitung dalam angka apa pun yang menjumlahkan seluruh tabel.
        antrean.hapusButirAntrean(tenantId, nama);

        if (antrean.hapus(tenantId, nama) == 0) {
            throw ApiException.tidakAda("Antrean tidak ada.");
        }
    }

    public List<Map<String, Object>> butir(UUID tenantId, String nama, String status, Integer batas) {
        QueueItemStatus keadaan = QueueItemStatus.dari(status);

        return antrean.butir(tenantId, nama, keadaan == null ? null : keadaan.name(),
                Batas.antara(batas, BATAS_BUTIR_BAWAAN, BATAS_BUTIR_MAKS));
    }

    @Transactional
    public Map<String, Object> tambahButir(UUID tenantId, String nama, Permintaan.ButirAntrean minta) {
        Map<String, Object> setelan = antrean.satu(tenantId, nama);

        if (setelan == null) throw ApiException.tidakAda("Antrean '" + nama + "' tidak ada.");

        boolean bolehKembar = Boolean.TRUE.equals(setelan.get("acceptDuplicates"));

        // Penolakan kembar hanya berlaku untuk butir yang BELUM selesai.
        // Referensi yang sama boleh muncul lagi besok; yang tidak boleh adalah
        // dua salinan menunggu diproses pada saat yang sama.
        if (!bolehKembar && minta.reference() != null && !minta.reference().isEmpty()
                && antrean.adaKembar(tenantId, nama, minta.reference())) {

            throw ApiException.sudahAda("Butir dengan referensi '" + minta.reference()
                    + "' sudah menunggu di antrean ini.");
        }

        UUID id = Db.newId();
        antrean.tambahButir(id, tenantId, nama, minta.reference(), minta.priority(), minta.content());

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("id", id.toString());

        return hasil;
    }

    @Transactional
    public Map<String, Object> ambilButir(UUID tenantId, String nama, String robot) {
        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("item", antrean.ambilButir(tenantId, nama, robot));

        return hasil;
    }

    /**
     * Hasil pemrosesan satu butir.
     *
     * <p>Butir gagal dicoba lagi selama jatah percobaannya belum habis. Sesudah
     * habis ia berhenti dan menghasilkan peringatan — butir yang dicoba
     * selamanya adalah butir yang tidak pernah ketahuan rusaknya.
     */
    @Transactional
    public Map<String, Object> hasilButir(UUID tenantId, String id, Permintaan.HasilButir minta) {
        UUID itemId = uuid(id);

        QueueItemStatus status = QueueItemStatus.dari(minta.status());

        if (status == null || !status.bolehDilaporkan()) {
            throw ApiException.salah("Status hasil tidak dikenal: '" + minta.status() + "'.");
        }

        Map<String, Object> butir = antrean.satuButir(tenantId, itemId);

        if (butir == null) throw ApiException.tidakAda("Butir antrean tidak ada.");

        String namaAntrean = (String) butir.get("queueName");
        long percobaan = ((Number) butir.get("retries")).longValue();

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);

        if (status == QueueItemStatus.FAILED) {
            Map<String, Object> setelan = antrean.satu(tenantId, namaAntrean);
            long maksimum = setelan == null ? 0 : ((Number) setelan.get("maxRetries")).longValue();

            if (percobaan < maksimum) {
                antrean.cobaLagi(tenantId, itemId, minta.exception());

                hasil.put("retried", true);
                hasil.put("attempt", percobaan + 1);

                return hasil;
            }

            Object referensi = butir.get("reference");

            catatan.catatPeringatan(tenantId, Severity.Warning, "Butir antrean gagal permanen",
                    "Butir '" + (referensi == null ? id : referensi) + "' di antrean " + namaAntrean
                            + " gagal setelah " + percobaan + " percobaan ulang.", "queues");
        }

        antrean.selesaikanButir(tenantId, itemId, status, minta.output(), minta.exception());

        hasil.put("retried", false);

        return hasil;
    }

    public void hapusButir(UUID tenantId, String id) {
        if (antrean.hapusButir(tenantId, uuid(id)) == 0) {
            throw ApiException.tidakAda("Butir antrean tidak ada.");
        }
    }

    private static UUID uuid(String id) {
        UUID hasil = Db.uuid(id);

        if (hasil == null) throw ApiException.tidakAda("Butir antrean tidak ada.");

        return hasil;
    }
}
