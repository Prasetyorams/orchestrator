package id.jakforge.forgehub.model;

/** Tingkat sebuah peringatan di dasbor. */
public enum Severity {
    Info,
    Warning,
    Error;

    public String nilai() {
        return name();
    }
}
