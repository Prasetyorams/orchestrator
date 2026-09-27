package id.jakforge.openorchestrator.dto.request;

import id.jakforge.openorchestrator.common.Strings;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Profil milik pengguna yang sedang masuk (PUT /api/auth/me).
 *
 * <p>Hanya nama tampilan dan surel. Nama pengguna dan peran sengaja tidak
 * ada di sini: nama pengguna dipakai untuk masuk — termasuk oleh robot
 * lewat jakrunner.json — dan peran yang bisa diubah pemiliknya sendiri
 * bukan lagi pembatasan.
 *
 * <p>Batas panjang mengikuti kolomnya di V1__init.sql. Bentuk surel diperiksa
 * layanan, SESUDAH panjangnya — urutan pesan yang sama dengan sebelumnya.
 *
 * @param email kosong berarti dihapus
 */
public record UpdateProfileRequest(
        @NotNull(message = "Nama tampilan wajib diisi.")
        @Size(max = UpdateProfileRequest.MAX_DISPLAY_NAME_LENGTH,
                message = "Nama tampilan paling panjang {max} karakter.")
        String displayName,

        @Size(max = UpdateProfileRequest.MAX_EMAIL_LENGTH, message = "Alamat surel paling panjang {max} karakter.")
        String email) {

    public static final int MAX_DISPLAY_NAME_LENGTH = 200;
    public static final int MAX_EMAIL_LENGTH = 160;

    public UpdateProfileRequest {
        displayName = Strings.trimToNull(displayName);
        email = Strings.trimToNull(email);
    }
}
