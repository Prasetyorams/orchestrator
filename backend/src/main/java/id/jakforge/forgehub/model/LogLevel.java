package id.jakforge.forgehub.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Tingkat sebuah baris catatan. */
public enum LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    WARNING,
    ERROR,
    FATAL;

    /** Perlu ikut muncul sebagai peringatan di kepala halaman. */
    public boolean raisesAlert() {
        return this == ERROR || this == FATAL;
    }

    /**
     * Tingkat RINCIAN — TRACE dan DEBUG — tidak disimpan dan tidak ditampilkan
     * ForgeHub.
     *
     * <p>Isinya jejak per-activity yang dikirim JakRunner untuk setiap langkah
     * workflow: satu perulangan seribu putaran menghasilkan ribuan baris yang
     * mengubur baris INFO, WARN, dan ERROR yang sebenarnya dicari orang di
     * orkestrator. Robot tetap menuliskannya ke log hariannya sendiri, jadi
     * jejak rinci satu jalan masih bisa dibaca di mesin robotnya.
     */
    public boolean isVerbose() {
        return this == TRACE || this == DEBUG;
    }

    /** Nama tingkat rincian, untuk menyaring baris lama yang terlanjur tersimpan. */
    public static List<String> verboseLevelNames() {
        return Arrays.stream(values()).filter(LogLevel::isVerbose).map(Enum::name).toList();
    }

    /**
     * Nama yang tersimpan di kolom level untuk tingkat ini.
     *
     * <p>WARN dan WARNING adalah SATU tingkat dengan dua ejaan: JakRunner
     * mengirim WARN, klien lain menulis WARNING, dan keduanya diterima apa
     * adanya. Penyaring yang memilih "WARN" tapi melewatkan baris WARNING akan
     * menyembunyikan peringatan yang justru sedang dicari.
     */
    public List<String> storedSpellings() {
        return this == WARN || this == WARNING ? List.of(WARN.name(), WARNING.name()) : List.of(name());
    }

    /**
     * Urai dengan KETAT: null kalau tidak dikenal.
     *
     * <p>Untuk penyaring saat membaca, bukan untuk baris yang dikirim robot.
     * Salah ketik pada penyaring harus terlihat sebagai kesalahan, bukan
     * diam-diam berubah menjadi "INFO" dan menampilkan hasil yang tidak diminta.
     */
    public static LogLevel parseStrict(String text) {
        if (text == null || text.isBlank()) return null;

        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Urai, dengan INFO sebagai jatuhan.
     *
     * <p>Tidak mengembalikan null: satu salah ketik pada satu baris tidak boleh
     * membuang seluruh kiriman log. Yang hilang kemudian justru catatan di
     * sekitar kegagalan yang sedang dicari orang.
     */
    public static LogLevel parseOrInfo(String text) {
        LogLevel level = parseStrict(text);
        return level == null ? INFO : level;
    }
}
