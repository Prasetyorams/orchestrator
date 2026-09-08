package id.jakforge.forgehub.data;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pembantu kueri yang tipis.
 *
 * <p>Tidak ada ORM di sini dengan sengaja, dan alasannya sama dengan yang
 * ditulis di sisi .NET: endpoint ForgeHub semuanya berupa "ambil beberapa
 * baris lalu kirim sebagai JSON". Entitas JPA di tengahnya menambah lapisan
 * yang harus dipahami tanpa menghilangkan satu pun SQL yang benar-benar
 * ditulis — dan di sini ada alasan tambahan: bentuk JSON-nya harus SAMA PERSIS
 * dengan yang sudah dikirim ForgeHub .NET hari ini, karena Studio dan JakRunner
 * membacanya. Memetakan lewat entitas berarti bentuk itu ditentukan oleh
 * kebetulan penamaan medan, bukan oleh keputusan.
 *
 * <p>SEMUA nilai masuk lewat parameter, tidak pernah lewat perangkaian string.
 * Nama proses dan nama robot datang dari luar, dan satu tanda kutip di sana
 * sudah cukup untuk mengubah kueri menjadi sesuatu yang lain.
 */
@Component
public class Db {

    private final JdbcTemplate jdbc;

    public Db(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    // -----------------------------------------------------------------
    // Membaca
    // -----------------------------------------------------------------

    /** Semua baris sebagai peta kolom-ke-nilai, siap jadi JSON. */
    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.query(sql, Db::mapRow, args);
    }

    /** Baris pertama, atau null kalau tidak ada. */
    public Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.query(sql, Db::mapRow, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Object scalar(String sql, Object... args) {
        List<Object> hasil = jdbc.query(sql, (rs, i) -> rs.getObject(1), args);
        return hasil.isEmpty() ? null : hasil.get(0);
    }

    public long count(String sql, Object... args) {
        Object v = scalar(sql, args);
        return v == null ? 0L : ((Number) v).longValue();
    }

    public boolean exists(String sql, Object... args) {
        return count(sql, args) > 0;
    }

    // -----------------------------------------------------------------
    // Menulis
    // -----------------------------------------------------------------

    public int exec(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    // -----------------------------------------------------------------
    // Bentuk nilai
    // -----------------------------------------------------------------

    /**
     * Satu baris menjadi peta, dengan dua penyesuaian yang keduanya penting
     * untuk kecocokan dengan ForgeHub .NET.
     */
    private static Map<String, Object> mapRow(ResultSet rs, int nomorBaris) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int jumlah = meta.getColumnCount();

        Map<String, Object> baris = new LinkedHashMap<>(jumlah * 2);

        for (int i = 1; i <= jumlah; i++) {
            baris.put(camel(meta.getColumnLabel(i)), nilai(rs.getObject(i)));
        }

        return baris;
    }

    /**
     * Nilai yang ramah JSON.
     *
     * <p>Waktu diubah menjadi UNTAI ISO-8601, bukan dibiarkan sebagai objek
     * waktu. Itu bukan sekadar selera: ForgeHub .NET menyimpan waktu sebagai
     * teks dan mengirimkannya apa adanya, jadi dasbor dan robot sudah menerima
     * untai. Membiarkan Jackson memilih bentuknya sendiri berarti bentuknya
     * bisa berubah hanya karena setelan Jackson berubah — dan yang patah
     * kemudian adalah pengurai di sisi lain, jauh dari sini.
     *
     * <p>UUID juga menjadi untai, dengan alasan yang sama: klien memperlakukan
     * id sebagai teks buram yang dikirim balik apa adanya.
     */
    private static Object nilai(Object v) throws SQLException {
        if (v == null) return null;

        if (v instanceof java.sql.Timestamp t) {
            return ISO.format(t.toInstant().atOffset(ZoneOffset.UTC));
        }

        if (v instanceof OffsetDateTime o) {
            return ISO.format(o.withOffsetSameInstant(ZoneOffset.UTC));
        }

        if (v instanceof Instant i) {
            return ISO.format(i.atOffset(ZoneOffset.UTC));
        }

        if (v instanceof UUID u) {
            return u.toString();
        }

        // BYTEA tidak pernah dikirim sebagai bagian dari JSON — isi paket
        // diunduh lewat endpoint tersendiri sebagai aliran bita. Kalau sampai
        // ikut terbawa di sebuah SELECT, yang dikirim adalah ukurannya, bukan
        // isinya: satu paket 17 KB menjadi 23 KB base64 di dalam JSON yang
        // sebenarnya cuma dipakai untuk daftar.
        if (v instanceof byte[] b) {
            return b.length;
        }

        if (v instanceof Array a) {
            return a.getArray();
        }

        return v;
    }

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    /**
     * snake_case di basis data, camelCase di JSON.
     *
     * <p>Kedua sisi memakai kebiasaan masing-masing; penerjemahannya cukup di
     * satu tempat ini, bukan ditulis ulang di setiap kueri sebagai alias AS.
     */
    private static String camel(String kolom) {
        if (kolom == null || kolom.indexOf('_') < 0) return kolom;

        StringBuilder sb = new StringBuilder(kolom.length());
        boolean besarkan = false;

        for (int i = 0; i < kolom.length(); i++) {
            char c = kolom.charAt(i);

            if (c == '_') {
                besarkan = true;
                continue;
            }

            sb.append(besarkan ? Character.toUpperCase(c) : c);
            besarkan = false;
        }

        return sb.toString();
    }

    // -----------------------------------------------------------------
    // Nilai baru
    // -----------------------------------------------------------------

    public static UUID newId() {
        return UUID.randomUUID();
    }

    /** Sekarang, dalam bentuk yang sama dengan yang dikirim ke klien. */
    public static String nowText() {
        return ISO.format(OffsetDateTime.now(ZoneOffset.UTC));
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Ubah teks menjadi UUID, atau null kalau bukan UUID.
     *
     * <p>Dipakai untuk id yang datang dari URL. Melemparkan pengecualian di
     * sini akan menghasilkan 500 untuk sesuatu yang sebenarnya permintaan
     * salah bentuk — dan 500 mengarahkan orang mencari kerusakan di server.
     */
    public static UUID uuid(String teks) {
        if (teks == null || teks.isBlank()) return null;

        try {
            return UUID.fromString(teks.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Daftar kosong yang bisa dikirim apa adanya sebagai JSON. */
    public static List<Map<String, Object>> kosong() {
        return new ArrayList<>();
    }
}
