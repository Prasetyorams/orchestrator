package id.jakforge.openorchestrator.common;

import java.util.UUID;

/** Penguraian id dari teks dan dari nilai kolom. */
public final class Uuids {

    private Uuids() {
    }

    /**
     * Ubah teks menjadi UUID, atau null kalau bukan UUID.
     *
     * <p>Dipakai untuk id yang datang dari URL. Melemparkan pengecualian di
     * sini akan menghasilkan 500 untuk sesuatu yang sebenarnya permintaan
     * salah bentuk — dan 500 mengarahkan orang mencari kerusakan di server.
     */
    public static UUID parseOrNull(String text) {
        if (text == null || text.isBlank()) return null;

        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** UUID dari nilai kolom yang bisa berupa UUID atau teksnya. */
    public static UUID fromColumn(Object value) {
        if (value == null) return null;

        return value instanceof UUID uuid ? uuid : parseOrNull(String.valueOf(value));
    }
}
