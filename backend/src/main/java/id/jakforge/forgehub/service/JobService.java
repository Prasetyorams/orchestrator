package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.JobRequest;
import id.jakforge.forgehub.dto.JobStateRequest;
import id.jakforge.forgehub.model.JobState;
import id.jakforge.forgehub.model.Severity;
import id.jakforge.forgehub.repository.CatalogRepository;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.JobRepository;
import id.jakforge.forgehub.repository.LogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aturan tentang pekerjaan.
 *
 * <p>Yang ada di sini adalah keputusan, bukan SQL dan bukan HTTP: proses yang
 * belum diterbitkan tidak boleh dijadwalkan, keadaan yang tidak dikenal
 * ditolak, kegagalan menghasilkan peringatan.
 */
@Service
public class JobService {

    private static final int BATAS_DAFTAR_BAWAAN = 100;
    private static final int BATAS_DAFTAR_MAKS = 1000;

    private final JobRepository jobs;
    private final CatalogRepository katalog;
    private final LogRepository catatan;

    public JobService(JobRepository jobs, CatalogRepository katalog, LogRepository catatan) {
        this.jobs = jobs;
        this.katalog = katalog;
        this.catatan = catatan;
    }

    public List<Map<String, Object>> daftar(UUID tenantId, String state, String process, Integer batas) {
        // Keadaan dinormalkan lewat enum, bukan dengan toUpperCase mentah:
        // "?state=berjalan" tidak akan pernah cocok, dan lebih baik menyaring
        // dengan nilai yang jelas tidak ada daripada dengan untai sembarang.
        JobState keadaan = JobState.dari(state);

        return jobs.cari(tenantId, keadaan == null ? null : keadaan.name(), process,
                Batas.antara(batas, BATAS_DAFTAR_BAWAAN, BATAS_DAFTAR_MAKS));
    }

    public Map<String, Object> satu(UUID tenantId, String id) {
        Map<String, Object> job = jobs.satu(tenantId, uuid(id));

        if (job == null) throw ApiException.tidakAda("Pekerjaan tidak ada.");

        return job;
    }

    /**
     * Jadwalkan pekerjaan baru.
     *
     * <p>Proses yang belum diterbitkan DITOLAK di sini, bukan dibiarkan menjadi
     * pekerjaan PENDING yang tidak akan pernah bisa dijalankan robot mana pun —
     * dan yang terlihat kemudian adalah antrean yang menumpuk tanpa sebab.
     */
    @Transactional
    public Map<String, Object> buat(UUID tenantId, JobRequest minta) {
        if (minta.processName() == null) {
            throw ApiException.salah("processName wajib diisi.");
        }

        if (!katalog.adaProses(tenantId, minta.processName())) {
            throw ApiException.salah(
                    "Proses '" + minta.processName() + "' belum diterbitkan ke ForgeHub.");
        }

        UUID id = Db.newId();

        jobs.buat(id, tenantId, minta.processName(), minta.robotName(), minta.machineName(),
                minta.source(), minta.priority(), "Menunggu robot yang tersedia.", minta.inputJson());

        catatan.tulisSistem(tenantId,
                "Pekerjaan dijadwalkan untuk " + minta.processName() + ".",
                minta.processName(), id);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("id", id.toString());

        return hasil;
    }

    /**
     * Robot mengambil pekerjaan berikutnya.
     *
     * <p>Selalu berhasil, bahkan ketika tidak ada apa-apa: {@code {"job": null}}
     * adalah jawaban yang benar dan yang paling sering. Robot yang menerima 404
     * setiap beberapa detik akan memenuhi catatannya dengan galat yang bukan
     * galat.
     */
    @Transactional
    public Map<String, Object> ambilBerikutnya(UUID tenantId, String robot) {
        if (robot == null || robot.isBlank()) {
            throw ApiException.salah("Parameter 'robot' wajib diisi.");
        }

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("job", jobs.ambilBerikutnya(tenantId, robot));

        return hasil;
    }

    @Transactional
    public void ubahKeadaan(UUID tenantId, String id, JobStateRequest minta) {
        UUID jobId = uuid(id);

        JobState keadaan = JobState.dari(minta.state());

        if (keadaan == null) {
            throw ApiException.salah("Keadaan tidak dikenal: '" + minta.state() + "'.");
        }

        String proses = jobs.namaProses(tenantId, jobId);

        if (proses == null) throw ApiException.tidakAda("Pekerjaan tidak ada.");

        // Yang sudah selesai selalu 100%. Pekerjaan berhasil yang tercatat 40%
        // membuat orang mengira ada yang terhenti di tengah.
        int kemajuan = keadaan.selesai() ? 100 : Math.clamp(minta.progress(), 0, 100);

        jobs.ubahKeadaan(tenantId, jobId, keadaan, kemajuan, minta.info(), minta.outputJson());

        if (keadaan == JobState.FAULTED) {
            catatan.catatPeringatan(tenantId, Severity.Error, "Pekerjaan gagal",
                    proses + " gagal: " + (minta.info() == null ? "tanpa keterangan" : minta.info()),
                    "jobs");
        }
    }

    public void hentikan(UUID tenantId, String id) {
        if (jobs.hentikan(tenantId, uuid(id)) == 0) {
            throw ApiException.salah("Pekerjaan itu tidak sedang menunggu atau berjalan.");
        }
    }

    public void hapus(UUID tenantId, String id) {
        if (jobs.hapus(tenantId, uuid(id)) == 0) {
            throw ApiException.tidakAda("Pekerjaan tidak ada.");
        }
    }

    /**
     * Id dari URL menjadi UUID.
     *
     * <p>Yang bukan UUID dijawab 404, bukan 500: itu permintaan yang salah
     * bentuk, dan 500 mengarahkan orang mencari kerusakan di server.
     */
    private static UUID uuid(String id) {
        UUID hasil = Db.uuid(id);

        if (hasil == null) throw ApiException.tidakAda("Pekerjaan tidak ada.");

        return hasil;
    }
}
