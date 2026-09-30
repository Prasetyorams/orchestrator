package id.jakforge.openorchestrator.model;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Prioritas job: Low, Normal, High — dan "Inherited", yang berarti ikut
 * prioritas bawaan prosesnya.
 *
 * <p>Nilainya selalu disimpan dalam bentuk baku. Pengurutan klaim
 * membandingkan persis 'High' dan 'Normal', jadi "high" dari Studio dulu
 * diam-diam diperlakukan sebagai Low.
 */
public final class JobPriorities {

    public static final String LOW = "Low";
    public static final String NORMAL = "Normal";
    public static final String HIGH = "High";

    /** Bukan prioritas yang disimpan: diganti prioritas bawaan proses saat job dibuat. */
    public static final String INHERITED = "Inherited";

    /** Yang boleh disimpan di job dan di proses, dari yang terendah. */
    public static final List<String> STORED = List.of(LOW, NORMAL, HIGH);

    private JobPriorities() {
    }

    /**
     * Bentuk baku dari teks bebas, termasuk {@link #INHERITED}.
     *
     * @return kosong untuk teks kosong dan untuk yang tidak dikenal
     */
    public static Optional<String> parse(String text) {
        if (text == null || text.isBlank()) return Optional.empty();

        String wanted = text.trim().toLowerCase(Locale.ROOT);

        if (INHERITED.toLowerCase(Locale.ROOT).equals(wanted)) return Optional.of(INHERITED);

        return STORED.stream().filter(p -> p.toLowerCase(Locale.ROOT).equals(wanted)).findFirst();
    }

    /** Untuk yang tidak boleh gagal (pemicu): yang tidak dikenal menjadi Normal. */
    public static String storedOrNormal(String text) {
        return parse(text).filter(STORED::contains).orElse(NORMAL);
    }
}
