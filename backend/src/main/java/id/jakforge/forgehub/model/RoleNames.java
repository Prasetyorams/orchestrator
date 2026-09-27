package id.jakforge.forgehub.model;

/**
 * Nama peran yang punya arti khusus bagi kode.
 *
 * <p>Pengguna menyimpan NAMA perannya (kolom users.role), jadi nama inilah yang
 * dicocokkan — di kueri maupun di layanan.
 */
public final class RoleNames {

    /**
     * Peran yang selalu berarti semuanya, apa pun isi barisnya — dan barisnya
     * tidak bisa diubah atau dihapus. Tanpa itu, satu penyuntingan yang keliru
     * bisa mengunci semua orang di luar ForgeHub, tanpa ada yang tersisa untuk
     * membukanya lagi.
     */
    public static final String ADMINISTRATOR = "Administrator";

    /** Peran pengguna baru yang disimpan tanpa menyebut perannya. */
    public static final String AUTOMATION_USER = "Automation User";

    private RoleNames() {
    }
}
