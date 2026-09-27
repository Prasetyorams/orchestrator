package id.jakforge.forgehub.common;

import java.util.Map;

/**
 * Membaca badan permintaan yang bentuknya longgar (pola Tolerant Reader).
 *
 * <p>Dipakai HANYA oleh endpoint yang dipanggil robot dan Studio — denyut,
 * laporan pekerjaan, kiriman catatan, butir antrean, penerbitan paket. Klien
 * itu mengirim susunan yang sedikit berbeda antar versi untuk hal yang sama —
 * kadang angka sebagai angka, kadang sebagai untai; kadang medan yang tidak
 * dikenal ikut terbawa. Karena itu badan permintaannya dibaca PER MEDAN dengan
 * nilai bawaan, bukan dipetakan kaku ke sebuah kelas yang gagal seluruhnya
 * begitu ada satu medan yang tidak sesuai.
 *
 * <p>Ini menyalin perilaku ApiSupport di sisi .NET dengan sengaja: klien yang
 * sama harus diterima dengan cara yang sama. Kelas {@code @RequestBody} yang
 * ketat akan menolak permintaan yang hari ini berhasil, dan yang terlihat di
 * sisi robot cuma "400 Bad Request" tanpa petunjuk medan mana yang mengganggu.
 *
 * <p>Endpoint yang hanya dipanggil dasbor memakai record bertipe dengan
 * {@code @Valid}, karena dasbornya ada di repositori yang sama dan bentuk
 * kirimannya bisa dipastikan.
 */
public final class RequestBodies {

    private RequestBodies() {
    }

    /** Teks apa adanya, atau null kalau tidak dikirim atau kosong. */
    public static String text(Map<String, Object> body, String field) {
        return text(body, field, null);
    }

    public static String text(Map<String, Object> body, String field, String defaultValue) {
        Object value = valueOf(body, field);
        if (value == null) return defaultValue;

        String text = value instanceof String string ? string : String.valueOf(value);
        return text.isEmpty() ? defaultValue : text;
    }

    /** Teks yang sudah dipangkas, atau null kalau kosong — untuk nama dan kunci. */
    public static String trimmedText(Map<String, Object> body, String field) {
        return Strings.trimToNull(text(body, field, null));
    }

    public static double decimal(Map<String, Object> body, String field, double defaultValue) {
        Object value = valueOf(body, field);
        if (value == null) return defaultValue;

        if (value instanceof Number number) return number.doubleValue();

        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static int integer(Map<String, Object> body, String field, int defaultValue) {
        return (int) decimal(body, field, defaultValue);
    }

    public static boolean bool(Map<String, Object> body, String field, boolean defaultValue) {
        Object value = valueOf(body, field);
        if (value == null) return defaultValue;

        if (value instanceof Boolean flag) return flag;
        if (value instanceof Number number) return number.doubleValue() != 0;

        String text = String.valueOf(value).trim();

        // "0" dan "false" keduanya dianggap salah. JavaScript mengirim boolean
        // sebagai boolean, tapi formulir HTML mengirimnya sebagai untai, dan
        // untai "false" yang dianggap benar adalah bug yang sulit dilihat.
        if (text.equalsIgnoreCase("true") || text.equals("1")) return true;
        if (text.equalsIgnoreCase("false") || text.equals("0")) return false;

        return defaultValue;
    }

    /** Nilai mentah sebuah medan; null kalau badannya atau medannya tidak ada. */
    public static Object valueOf(Map<String, Object> body, String field) {
        return body == null ? null : body.get(field);
    }
}
