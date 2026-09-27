package id.jakforge.openorchestrator.model;

/** Tingkat sebuah peringatan di dasbor. */
public enum Severity {
    Info,
    Warning,
    Error;

    /** Ejaan yang tersimpan di kolom alerts.severity: Info, Warning, Error. */
    public String storedValue() {
        return name();
    }

    /** Urai tanpa peduli huruf besar-kecil; null kalau tidak dikenal. */
    public static Severity parse(String text) {
        if (text == null || text.isBlank()) return null;

        for (Severity severity : values()) {
            if (severity.name().equalsIgnoreCase(text.trim())) return severity;
        }

        return null;
    }
}
