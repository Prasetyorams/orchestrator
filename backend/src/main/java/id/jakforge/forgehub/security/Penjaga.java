package id.jakforge.forgehub.security;

/**
 * Pemeriksa izin untuk SATU permintaan, diserahkan controller ke layanan.
 *
 * <p>Ada untuk endpoint "simpan" yang membuat ATAU mengubah (POST /api/assets,
 * POST /api/processes, ...). Pencegat izin hanya melihat jalurnya, jadi di
 * sana cukup "boleh membuat atau mengubah"; yang tahu mana yang sebenarnya
 * terjadi adalah layanannya, sesudah ia memeriksa apakah namanya sudah ada.
 * Di titik itulah layanan memanggil {@link #perlu}.
 */
@FunctionalInterface
public interface Penjaga {

    /** Tanpa pemeriksaan — untuk uji dan untuk pekerjaan sistem seperti penjadwal. */
    Penjaga BEBAS = izin -> { };

    /** Melempar 403 kalau peran pemintanya tidak punya izin itu. */
    void perlu(String izin);

    /** "create" untuk yang baru, "update" untuk yang sudah ada. */
    default void perluSimpan(String sumber, boolean sudahAda) {
        perlu(sumber + (sudahAda ? ".update" : ".create"));
    }
}
