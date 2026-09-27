package id.jakforge.forgehub.model;

/**
 * Peran seseorang SAAT INI, apakah akunnya aktif, dan pola izin perannya.
 *
 * @param permissions pola izin dipisah koma, persis seperti kolom roles.permissions;
 *                    null kalau baris perannya tidak ada
 */
public record UserAccess(String role, boolean active, String permissions) {
}
