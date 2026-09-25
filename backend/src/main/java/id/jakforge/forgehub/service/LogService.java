package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.common.Badan;
import id.jakforge.forgehub.model.LogLevel;
import id.jakforge.forgehub.model.Severity;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.LogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * @param level tingkat yang ingin dilihat — satu atau lebih, dari
     *              {@code ?level=INFO,WARN} maupun {@code ?level=INFO&level=WARN}.
     *              Kosong berarti semua tingkat.
     */
    public List<Map<String, Object>> cari(UUID tenantId, List<String> level, String robot,
                                          String process, String jobId, Integer batas) {

        UUID job = null;

        if (jobId != null && !jobId.isBlank()) {
            job = Db.uuid(jobId);

            // Id yang bukan UUID tidak akan pernah cocok dengan apa pun. Yang
            // dikembalikan daftar kosong, bukan galat penguraian dari basis data.
            if (job == null) return Db.kosong();
        }

        Set<LogLevel> diminta = tingkatDiminta(level);

        // Tingkat rincian tidak pernah ditampilkan (lihat LogLevel.rincian).
        // Yang HANYA meminta rincian memang tidak mendapat apa-apa; yang
        // memintanya bersama tingkat lain mendapat tingkat lain itu saja.
        if (!diminta.isEmpty() && diminta.stream().allMatch(LogLevel::rincian)) return Db.kosong();

        Set<String> ejaan = new LinkedHashSet<>();

        for (LogLevel t : diminta) {
            if (!t.rincian()) ejaan.addAll(t.ejaan());
        }

        return catatan.cari(tenantId, ejaan, robot, process, job,
                Batas.antara(batas, BATAS_BAWAAN, BATAS_MAKS));
    }

    /**
     * Tingkat yang diminta, sebagai himpunan.
     *
     * <p>Koma dipecah di sini juga, bukan hanya diserahkan ke Spring: bentuk
     * {@code ?level=INFO,WARN} harus tetap berarti dua tingkat walaupun
     * parameternya kelak dibaca sebagai satu untai.
     */
    static Set<LogLevel> tingkatDiminta(List<String> mentah) {
        Set<LogLevel> hasil = EnumSet.noneOf(LogLevel.class);
        if (mentah == null) return hasil;

        for (String bagian : mentah) {
            if (bagian == null) continue;

            for (String teks : bagian.split(",")) {
                if (teks.isBlank()) continue;

                LogLevel tingkat = LogLevel.kenali(teks);

                if (tingkat == null) {
                    throw ApiException.salah("Tingkat catatan tidak dikenal: '" + teks.trim() + "'.");
                }

                hasil.add(tingkat);
            }
        }

        return hasil;
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
        int dilewati = 0;

        for (Object o : baris) {
            if (!(o instanceof Map)) continue;

            Map<String, Object> b = (Map<String, Object>) o;

            String pesan = Badan.teks(b, "message");
            if (pesan == null || pesan.isBlank()) continue;

            LogLevel tingkat = LogLevel.dari(Badan.teks(b, "level"));

            // TRACE dan DEBUG tidak disimpan — lihat LogLevel.rincian. Ditolak
            // di sini, bukan hanya disembunyikan saat dibaca: jejak per-activity
            // yang tidak pernah ditampilkan hanya memenuhi tabel dan
            // memperlambat setiap pencarian.
            if (tingkat.rincian()) {
                dilewati++;
                continue;
            }

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
        hasil.put("skipped", dilewati);

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

    /**
     * @param severity tingkat yang ingin dilihat — Info, Warning, Error; satu
     *                 atau lebih. Kosong berarti semua tingkat.
     */
    public List<Map<String, Object>> peringatan(UUID tenantId, String unread, List<String> severity,
                                                Integer batas) {
        // Menerima "1" maupun "true": dasbor lama mengirim "1", dan klien lain
        // yang menulis "true" tidak boleh diam-diam melihat seluruh daftar.
        boolean hanyaBelumDibaca = "1".equals(unread) || "true".equalsIgnoreCase(unread);

        return catatan.peringatan(tenantId, hanyaBelumDibaca, tingkatPeringatan(severity),
                Batas.antara(batas, 50, 500));
    }

    /** Sama seperti {@link #tingkatDiminta}, untuk tingkat peringatan. */
    static List<String> tingkatPeringatan(List<String> mentah) {
        Set<Severity> hasil = EnumSet.noneOf(Severity.class);
        if (mentah == null) return List.of();

        for (String bagian : mentah) {
            if (bagian == null) continue;

            for (String teks : bagian.split(",")) {
                if (teks.isBlank()) continue;

                Severity tingkat = Severity.dari(teks);

                if (tingkat == null) {
                    throw ApiException.salah("Tingkat peringatan tidak dikenal: '" + teks.trim() + "'.");
                }

                hasil.add(tingkat);
            }
        }

        return hasil.stream().map(Severity::nilai).toList();
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
