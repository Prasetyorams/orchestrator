package id.jakforge.openorchestrator.support;

import id.jakforge.openorchestrator.repository.Database;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Basis data palsu: merekam setiap perintah beserta argumennya, dan tidak
 * menjalankan apa pun.
 *
 * <p>Repositori dan layanannya ASLI; yang palsu hanya basis datanya. Dengan
 * begitu yang diuji juga kalimat SQL yang benar-benar dikirim, bukan hanya
 * keputusan di layanan.
 *
 * <p>Jawabannya kosong — kecuali baris yang disiapkan lewat {@link #answerRow}.
 * Kuncinya sudah camelCase, seperti yang dikembalikan {@link Database} yang asli.
 */
public class RecordingDatabase extends Database {

    public final List<String> statements = new ArrayList<>();
    public final List<Object[]> arguments = new ArrayList<>();

    private final Map<String, Map<String, Object>> rowsBySqlFragment = new LinkedHashMap<>();

    public RecordingDatabase() {
        super(null);
    }

    /** Kueri satu baris yang memuat potongan SQL itu dijawab dengan salinan baris ini. */
    public void answerRow(String sqlFragment, Map<String, Object> row) {
        rowsBySqlFragment.put(sqlFragment, row);
    }

    @Override
    public List<Map<String, Object>> queryRows(String sql, Object... args) {
        record(sql, args);
        return new ArrayList<>();
    }

    @Override
    public Optional<Map<String, Object>> queryRow(String sql, Object... args) {
        record(sql, args);

        for (Map.Entry<String, Map<String, Object>> answer : rowsBySqlFragment.entrySet()) {
            if (sql.contains(answer.getKey())) return Optional.of(new HashMap<>(answer.getValue()));
        }

        return Optional.of(new HashMap<>());
    }

    @Override
    public Optional<Object> queryScalar(String sql, Object... args) {
        record(sql, args);
        return Optional.of(0L);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        record(sql, args);
        return new ArrayList<>();
    }

    @Override
    public int update(String sql, Object... args) {
        record(sql, args);
        return 1;
    }

    // -----------------------------------------------------------------
    // Pembacaan hasil rekaman
    // -----------------------------------------------------------------

    public String lastStatement() {
        return statements.getLast();
    }

    public List<Object> lastArguments() {
        return Arrays.asList(arguments.getLast());
    }

    public String statementContaining(String sqlFragment) {
        return statements.get(indexOf(sqlFragment));
    }

    public List<Object> argumentsOf(String sqlFragment) {
        return Arrays.asList(arguments.get(indexOf(sqlFragment)));
    }

    public List<String> statementsContaining(String sqlFragment) {
        return statements.stream().filter(statement -> statement.contains(sqlFragment)).toList();
    }

    private int indexOf(String sqlFragment) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains(sqlFragment)) return i;
        }

        throw new AssertionError("Tidak ada SQL yang memuat: " + sqlFragment + "\n" + statements);
    }

    private void record(String sql, Object[] args) {
        statements.add(sql);
        arguments.add(args);
    }
}
