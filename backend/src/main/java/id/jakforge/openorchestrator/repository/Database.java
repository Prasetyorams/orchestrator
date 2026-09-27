package id.jakforge.openorchestrator.repository;

import id.jakforge.openorchestrator.common.Timestamps;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pembantu kueri yang tipis di atas {@link JdbcTemplate}.
 *
 * <p>Tidak ada ORM di sini dengan sengaja, dan alasannya sama dengan yang
 * ditulis di sisi .NET: endpoint OpenOrchestrator semuanya berupa "ambil beberapa
 * baris lalu kirim sebagai JSON". Entitas JPA di tengahnya menambah lapisan
 * yang harus dipahami tanpa menghilangkan satu pun SQL yang benar-benar
 * ditulis — dan di sini ada alasan tambahan: bentuk JSON-nya harus SAMA PERSIS
 * dengan yang sudah dikirim OpenOrchestrator .NET hari ini, karena Studio dan JakRunner
 * membacanya. Memetakan lewat entitas berarti bentuk itu ditentukan oleh
 * kebetulan penamaan medan, bukan oleh keputusan.
 *
 * <p>SEMUA nilai masuk lewat parameter, tidak pernah lewat perangkaian string.
 * Nama proses dan nama robot datang dari luar, dan satu tanda kutip di sana
 * sudah cukup untuk mengubah kueri menjadi sesuatu yang lain.
 *
 * <p>{@code @Component}, bukan {@code @Repository}: ini alat untuk repository,
 * bukan repository sebuah tabel. JdbcTemplate sudah menerjemahkan SQLException
 * menjadi DataAccessException.
 */
@Component
@RequiredArgsConstructor
public class Database {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    // -----------------------------------------------------------------
    // Membaca
    // -----------------------------------------------------------------

    /** Semua baris sebagai peta kolom-ke-nilai, siap jadi JSON. */
    public List<Map<String, Object>> queryRows(String sql, Object... args) {
        return jdbcTemplate.query(sql, Database::mapRow, args);
    }

    /** Baris pertama, atau kosong kalau tidak ada. */
    public Optional<Map<String, Object>> queryRow(String sql, Object... args) {
        return queryRows(sql, args).stream().findFirst();
    }

    /** Nilai kolom pertama baris pertama, atau kosong kalau tidak ada baris atau nilainya NULL. */
    public Optional<Object> queryScalar(String sql, Object... args) {
        List<Object> values = jdbcTemplate.query(sql, (rs, rowNumber) -> rs.getObject(1), args);
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
    }

    /** Baris dengan pemetaan sendiri, untuk hasil bertipe atau kolom BYTEA. */
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        return jdbcTemplate.query(sql, rowMapper, args);
    }

    public long count(String sql, Object... args) {
        return queryScalar(sql, args).map(value -> ((Number) value).longValue()).orElse(0L);
    }

    public boolean exists(String sql, Object... args) {
        return count(sql, args) > 0;
    }

    // -----------------------------------------------------------------
    // Menulis
    // -----------------------------------------------------------------

    /** INSERT, UPDATE, atau DELETE; mengembalikan jumlah baris yang berubah. */
    public int update(String sql, Object... args) {
        return jdbcTemplate.update(sql, args);
    }

    /** "?, ?, ?" — nilainya tetap dikirim sebagai parameter, tidak pernah ditempel ke SQL. */
    public static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    // -----------------------------------------------------------------
    // Bentuk nilai
    // -----------------------------------------------------------------

    /**
     * Satu baris menjadi peta, dengan dua penyesuaian yang keduanya penting
     * untuk kecocokan dengan OpenOrchestrator .NET.
     */
    private static Map<String, Object> mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();

        Map<String, Object> row = new LinkedHashMap<>(columnCount * 2);

        for (int column = 1; column <= columnCount; column++) {
            row.put(toCamelCase(metaData.getColumnLabel(column)), toJsonValue(resultSet.getObject(column)));
        }

        return row;
    }

    /**
     * Nilai yang ramah JSON.
     *
     * <p>Waktu diubah menjadi UNTAI ISO-8601, bukan dibiarkan sebagai objek
     * waktu. Itu bukan sekadar selera: OpenOrchestrator .NET menyimpan waktu sebagai
     * teks dan mengirimkannya apa adanya, jadi dasbor dan robot sudah menerima
     * untai. Membiarkan Jackson memilih bentuknya sendiri berarti bentuknya
     * bisa berubah hanya karena setelan Jackson berubah — dan yang patah
     * kemudian adalah pengurai di sisi lain, jauh dari sini.
     *
     * <p>UUID juga menjadi untai, dengan alasan yang sama: klien memperlakukan
     * id sebagai teks buram yang dikirim balik apa adanya.
     */
    private static Object toJsonValue(Object value) throws SQLException {
        if (value == null) return null;

        if (value instanceof Timestamp timestamp) return Timestamps.format(timestamp.toInstant());
        if (value instanceof OffsetDateTime offsetDateTime) return Timestamps.format(offsetDateTime);
        if (value instanceof Instant instant) return Timestamps.format(instant);
        if (value instanceof UUID uuid) return uuid.toString();

        // BYTEA tidak pernah dikirim sebagai bagian dari JSON — isi paket
        // diunduh lewat endpoint tersendiri sebagai aliran bita. Kalau sampai
        // ikut terbawa di sebuah SELECT, yang dikirim adalah ukurannya, bukan
        // isinya: satu paket 17 KB menjadi 23 KB base64 di dalam JSON yang
        // sebenarnya cuma dipakai untuk daftar.
        if (value instanceof byte[] bytes) return bytes.length;

        if (value instanceof Array array) return array.getArray();

        return value;
    }

    /**
     * snake_case di basis data, camelCase di JSON.
     *
     * <p>Kedua sisi memakai kebiasaan masing-masing; penerjemahannya cukup di
     * satu tempat ini, bukan ditulis ulang di setiap kueri sebagai alias AS.
     */
    private static String toCamelCase(String columnName) {
        if (columnName == null || columnName.indexOf('_') < 0) return columnName;

        StringBuilder camelCase = new StringBuilder(columnName.length());
        boolean upperNext = false;

        for (int i = 0; i < columnName.length(); i++) {
            char character = columnName.charAt(i);

            if (character == '_') {
                upperNext = true;
                continue;
            }

            camelCase.append(upperNext ? Character.toUpperCase(character) : character);
            upperNext = false;
        }

        return camelCase.toString();
    }
}
