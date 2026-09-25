package id.jakforge.forgehub.model;

/** Tingkat sebuah peringatan di dasbor. */
public enum Severity {
    Info,
    Warning,
    Error;

    public String nilai() {
        return name();
    }

    /** Urai tanpa peduli huruf besar-kecil; null kalau tidak dikenal. */
    public static Severity dari(String teks) {
        if (teks == null || teks.isBlank()) return null;

        for (Severity s : values()) {
            if (s.name().equalsIgnoreCase(teks.trim())) return s;
        }

        return null;
    }
}
