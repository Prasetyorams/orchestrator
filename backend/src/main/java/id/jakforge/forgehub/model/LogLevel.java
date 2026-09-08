package id.jakforge.forgehub.model;

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
    public boolean gawat() {
        return this == ERROR || this == FATAL;
    }

    /**
     * Urai, dengan INFO sebagai jatuhan.
     *
     * <p>Tidak mengembalikan null: satu salah ketik pada satu baris tidak boleh
     * membuang seluruh kiriman log. Yang hilang kemudian justru catatan di
     * sekitar kegagalan yang sedang dicari orang.
     */
    public static LogLevel dari(String teks) {
        if (teks == null || teks.isBlank()) return INFO;

        try {
            return valueOf(teks.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return INFO;
        }
    }
}
