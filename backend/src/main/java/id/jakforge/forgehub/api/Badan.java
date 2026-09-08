package id.jakforge.forgehub.api;

import java.util.Map;

/**
 * Membaca badan permintaan yang bentuknya longgar.
 *
 * <p>Studio, JakRunner, dan dasbor mengirim susunan yang sedikit berbeda untuk
 * hal yang sama — kadang angka sebagai angka, kadang sebagai untai; kadang
 * medan yang tidak dikenal ikut terbawa. Karena itu badan permintaan dibaca
 * PER MEDAN dengan nilai bawaan, bukan dipetakan kaku ke sebuah kelas yang
 * gagal seluruhnya begitu ada satu medan tak dikenal.
 *
 * <p>Ini menyalin perilaku ApiSupport di sisi .NET dengan sengaja: klien yang
 * sama harus diterima dengan cara yang sama. Kelas @RequestBody yang ketat
 * akan menolak permintaan yang hari ini berhasil, dan yang terlihat di sisi
 * robot cuma "400 Bad Request" tanpa petunjuk medan mana yang mengganggu.
 */
public final class Badan {

    private Badan() {
    }

    public static String teks(Map<String, Object> body, String nama) {
        return teks(body, nama, null);
    }

    public static String teks(Map<String, Object> body, String nama, String bawaan) {
        Object v = ambil(body, nama);
        if (v == null) return bawaan;

        String s = v instanceof String str ? str : String.valueOf(v);
        return s.isEmpty() ? bawaan : s;
    }

    /** Teks yang sudah dipangkas, atau null kalau kosong — untuk nama dan kunci. */
    public static String nama(Map<String, Object> body, String nama) {
        String s = teks(body, nama, null);
        if (s == null) return null;

        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    public static double angka(Map<String, Object> body, String nama, double bawaan) {
        Object v = ambil(body, nama);
        if (v == null) return bawaan;

        if (v instanceof Number n) return n.doubleValue();

        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return bawaan;
        }
    }

    public static int bulat(Map<String, Object> body, String nama, int bawaan) {
        return (int) angka(body, nama, bawaan);
    }

    public static boolean benar(Map<String, Object> body, String nama, boolean bawaan) {
        Object v = ambil(body, nama);
        if (v == null) return bawaan;

        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;

        String s = String.valueOf(v).trim();

        // "0" dan "false" keduanya dianggap salah. JavaScript mengirim boolean
        // sebagai boolean, tapi formulir HTML mengirimnya sebagai untai, dan
        // untai "false" yang dianggap benar adalah bug yang sulit dilihat.
        if (s.equalsIgnoreCase("true") || s.equals("1")) return true;
        if (s.equalsIgnoreCase("false") || s.equals("0")) return false;

        return bawaan;
    }

    private static Object ambil(Map<String, Object> body, String nama) {
        if (body == null) return null;

        Object v = body.get(nama);
        return v == null ? null : v;
    }

    /**
     * Batasi jumlah baris yang diminta.
     *
     * <p>Tanpa batas atas, satu permintaan {@code ?limit=999999999} membaca
     * seluruh tabel log ke dalam memori dan menjatuhkan layanannya — dan itu
     * tidak perlu niat jahat, cukup satu salah ketik.
     */
    public static int batas(Integer diminta, int bawaan, int maksimum) {
        if (diminta == null) return bawaan;

        return Math.max(1, Math.min(diminta, maksimum));
    }
}
