package id.jakforge.openorchestrator.model;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Tipe runtime mesin: jenis job yang dilayani sebuah mesin, dan berapa banyak
 * sekaligus (tabel {@code machine_runtimes}).
 *
 * <p>Katalognya tetap, seperti di UiPath — bukan daftar yang bisa ditambah
 * sendiri. Tipe runtime menentukan ke mesin mana job boleh pergi; nama yang
 * diketik bebas akan menghasilkan "Produksi" dan "Production" yang tidak
 * pernah saling cocok.
 */
public final class RuntimeTypes {

    public static final String PRODUCTION = "Production";
    public static final String TESTING = "Testing";
    public static final String DEVELOPMENT = "Development";

    /**
     * Urutan tampil, dan urutan pilih saat job tanpa tipe runtime diambil
     * robot: Production lebih dulu, karena itu yang dipakai job sebelum tipe
     * runtime ada.
     */
    public static final List<String> ALL = List.of(PRODUCTION, TESTING, DEVELOPMENT);

    /** Runtime satu tipe di satu mesin — sama dengan batas slot mesin di V8. */
    public static final int MAX_SLOTS = 50;

    private RuntimeTypes() {
    }

    /**
     * Nama baku dari teks bebas: "testing" menjadi "Testing".
     *
     * @return kosong untuk teks kosong DAN untuk tipe yang tidak dikenal —
     *         pemanggil yang membedakan keduanya lewat {@link #isBlank}
     */
    public static Optional<String> parse(String text) {
        if (isBlank(text)) return Optional.empty();

        String wanted = text.trim().toLowerCase(Locale.ROOT);
        return ALL.stream().filter(type -> type.toLowerCase(Locale.ROOT).equals(wanted)).findFirst();
    }

    public static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** Urutan katalog sebagai ekspresi SQL — nama kolomnya dari kode, bukan dari masukan. */
    public static String sqlOrder(String column) {
        StringBuilder sql = new StringBuilder("CASE ").append(column);

        for (int i = 0; i < ALL.size(); i++) {
            sql.append(" WHEN '").append(ALL.get(i)).append("' THEN ").append(i);
        }

        return sql.append(" ELSE ").append(ALL.size()).append(" END").toString();
    }
}
