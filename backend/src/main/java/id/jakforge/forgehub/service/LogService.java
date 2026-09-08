package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.common.Badan;
import id.jakforge.forgehub.model.LogLevel;
import id.jakforge.forgehub.model.Severity;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.LogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Aturan tentang catatan dan peringatan. */
@Service
public class LogService {

    private static final int BATAS_BAWAAN = 200;

    /**
     * Batas atas satu permintaan.
     *
     * <p>2000 baris kira-kira satu megabita JSON. Lebih dari itu bukan lagi
     * "melihat catatan", melainkan mengunduh basis data lewat pintu yang tidak
     * dirancang untuk itu.
     */
    private static final int BATAS_MAKS = 2000;

    /** Batas baris per kiriman dari robot. */
    private static final int MAKS_BARIS_KIRIMAN = 1000;

    private final LogRepository catatan;

    public LogService(LogRepository catatan) {
        this.catatan = catatan;
    }

    // -----------------------------------------------------------------
    // Catatan
    // -----------------------------------------------------------------

    public List<Map<String, Object>> cari(UUID tenantId, String level, String robot,
                                          String process, String jobId, Integer batas) {

        UUID job = null;

        if (jobId != null && !jobId.isBlank()) {
            job = Db.uuid(jobId);

            // Id yang bukan UUID tidak akan pernah cocok dengan apa pun. Yang
            // dikembalikan daftar kosong, bukan galat penguraian dari basis data.
            if (job == null) return Db.kosong();
        }

        String tingkat = level == null || level.isBlank() ? null : LogLevel.dari(level).name();

        return catatan.cari(tenantId, tingkat, robot, process, job,
                Batas.antara(batas, BATAS_BAWAAN, BATAS_MAKS));
    }

    /**
     * Tulis sekelompok baris dari robot.
     *
     * <p>Baris yang cacat DILEWAT, bukan menggagalkan seluruh kiriman: satu
     * salah ketik pada satu baris tidak boleh membuang sembilan ratus baris
     * lain, dan yang hilang kemudian justru catatan di sekitar kegagalan yang
     * sedang dicari orang.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> tulis(UUID tenantId, Object mentah) {
        if (!(mentah instanceof List<?> baris)) {
            throw ApiException.salah("Butuh { \"lines\": [ ... ] }.");
        }

        if (baris.size() > MAKS_BARIS_KIRIMAN) {
            throw ApiException.salah(
                    "Terlalu banyak baris dalam satu kiriman. Batasnya " + MAKS_BARIS_KIRIMAN + ".");
        }

        int ditulis = 0;

        for (Object o : baris) {
            if (!(o instanceof Map)) continue;

            Map<String, Object> b = (Map<String, Object>) o;

            String pesan = Badan.teks(b, "message");
            if (pesan == null || pesan.isBlank()) continue;

            LogLevel tingkat = LogLevel.dari(Badan.teks(b, "level"));

            catatan.tulis(tenantId, tingkat, pesan,
                    Badan.teks(b, "robotName"), Badan.teks(b, "machineName"),
                    Badan.teks(b, "processName"), Db.uuid(Badan.teks(b, "jobId")),
                    Badan.teks(b, "loggedAt"));

            ditulis++;

            // Kesalahan dari robot juga menjadi peringatan. Log dibaca kalau ada
            // yang sengaja mencarinya; peringatan muncul sendiri.
            if (tingkat.gawat()) {
                catatan.catatPeringatan(tenantId, Severity.Error,
                        "Kesalahan pada " + Badan.teks(b, "robotName", "robot"), pesan, "logs");
            }
        }

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("written", ditulis);

        return hasil;
    }

    /**
     * Buang catatan lama.
     *
     * <p>Batas hari WAJIB dan minimal satu: "hapus semua log" adalah perintah
     * yang tidak bisa dibatalkan, dan bentuk yang paling mudah dijalankan tanpa
     * sengaja.
     */
    public Map<String, Object> bersihkan(UUID tenantId, Integer hari) {
        if (hari == null || hari < 1) {
            throw ApiException.salah("Parameter 'olderThanDays' wajib diisi dan minimal 1.");
        }

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("deleted", catatan.buangLebihTuaDari(tenantId, hari));

        return hasil;
    }

    // -----------------------------------------------------------------
    // Peringatan
    // -----------------------------------------------------------------

    public List<Map<String, Object>> peringatan(UUID tenantId, String unread, Integer batas) {
        // Menerima "1" maupun "true": dasbor lama mengirim "1", dan klien lain
        // yang menulis "true" tidak boleh diam-diam melihat seluruh daftar.
        boolean hanyaBelumDibaca = "1".equals(unread) || "true".equalsIgnoreCase(unread);

        return catatan.peringatan(tenantId, hanyaBelumDibaca, Batas.antara(batas, 50, 500));
    }

    public Map<String, Object> tandaiDibaca(UUID tenantId, long id) {
        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("changed", catatan.tandaiDibaca(tenantId, id));

        return hasil;
    }

    public Map<String, Object> tandaiSemuaDibaca(UUID tenantId) {
        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("changed", catatan.tandaiSemuaDibaca(tenantId));

        return hasil;
    }
}
