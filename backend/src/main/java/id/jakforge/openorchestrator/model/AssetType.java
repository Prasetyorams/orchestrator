package id.jakforge.openorchestrator.model;

/** Jenis nilai yang disimpan sebuah aset. */
public enum AssetType {
    Text,
    Integer,
    Bool,
    /**
     * Nama pengguna dan kata sandi. Nama penggunanya di kolom username, kata
     * sandinya — tersandi — di value_text. Sejak V3 inilah bentuk seluruh
     * kredensial; tabel credentials yang terpisah sudah tidak dipakai.
     */
    Credential,
    Secret;

    /**
     * Isinya disandikan sebelum disimpan dan tidak pernah muncul di daftar.
     *
     * <p>Sifat ini melekat pada JENISNYA, bukan pada keputusan tiap endpoint.
     * Sebelumnya syaratnya ditulis ulang di tiga tempat, dan satu tempat yang
     * lupa akan menaburkan kata sandi ke layar tanpa ada yang menyadarinya.
     */
    public boolean isSecret() {
        return this == Credential || this == Secret;
    }

    /** Tanpa peduli huruf besar-kecil; null kalau tidak dikenal. */
    public static AssetType parse(String text) {
        if (text == null || text.isBlank()) return null;

        for (AssetType type : values()) {
            if (type.name().equalsIgnoreCase(text.trim())) return type;
        }

        return null;
    }
}
