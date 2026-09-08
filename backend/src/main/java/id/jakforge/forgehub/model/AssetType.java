package id.jakforge.forgehub.model;

/** Jenis nilai yang disimpan sebuah aset. */
public enum AssetType {
    Text,
    Integer,
    Bool,
    Credential,
    Secret;

    /**
     * Isinya disandikan sebelum disimpan dan tidak pernah muncul di daftar.
     *
     * <p>Sifat ini melekat pada JENISNYA, bukan pada keputusan tiap endpoint.
     * Sebelumnya syaratnya ditulis ulang di tiga tempat, dan satu tempat yang
     * lupa akan menaburkan kata sandi ke layar tanpa ada yang menyadarinya.
     */
    public boolean rahasia() {
        return this == Credential || this == Secret;
    }

    public static AssetType dari(String teks) {
        if (teks == null || teks.isBlank()) return null;

        for (AssetType t : values()) {
            if (t.name().equalsIgnoreCase(teks.trim())) return t;
        }

        return null;
    }
}
