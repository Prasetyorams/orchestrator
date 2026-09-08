package id.jakforge.forgehub.repository;

import id.jakforge.forgehub.model.JobState;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Akses data pekerjaan.
 *
 * <p>SELURUH SQL tentang pekerjaan ada di sini dan tidak di tempat lain. Itu
 * gunanya lapisan ini: kalau kolom {@code jobs} berubah, yang harus dibaca
 * hanya berkas ini.
 *
 * <p>Yang dikembalikan adalah BARIS, bukan entitas bertipe. Bentuk baris SQL
 * adalah bentuk JSON yang dibaca Studio, JakRunner, dan dasbor — memetakannya
 * lewat entitas berarti bentuk itu ditentukan kebetulan penamaan medan, bukan
 * oleh kolom yang benar-benar dipilih di sini.
 */
@Repository
public class JobRepository {

    private static final String KOLOM_DAFTAR = """
            id, process_name, robot_name, machine_name, state, source, priority,
            progress, info, created_at, started_at, ended_at
            """;

    private static final String KOLOM_LENGKAP = """
            id, process_name, robot_name, machine_name, state, source, priority,
            progress, info, input_json, output_json, created_at, started_at, ended_at
            """;

    private final Db db;

    public JobRepository(Db db) {
        this.db = db;
    }

    public List<Map<String, Object>> cari(UUID tenantId, String state, String process, int batas) {
        // Penyaring dirangkai, bukan dijabarkan jadi empat kueri terpisah.
        // Halaman detail proses memerlukan "jalan milik proses ini saja", dan
        // menambahkannya sebagai cabang baru berarti empat kombinasi yang harus
        // dijaga tetap sama isinya.
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        where.add("tenant_id = ?");
        args.add(tenantId);

        if (state != null && !state.isBlank()) {
            where.add("state = ?");
            args.add(state);
        }

        if (process != null && !process.isBlank()) {
            where.add("process_name = ?");
            args.add(process);
        }

        args.add(batas);

        return db.rows("""
                SELECT %s
                  FROM jobs
                 WHERE %s
                 ORDER BY created_at DESC
                 LIMIT ?
                """.formatted(KOLOM_DAFTAR, String.join(" AND ", where)), args.toArray());
    }

    public Map<String, Object> satu(UUID tenantId, UUID id) {
        return db.row("SELECT %s FROM jobs WHERE tenant_id = ? AND id = ?".formatted(KOLOM_LENGKAP),
                tenantId, id);
    }

    public String namaProses(UUID tenantId, UUID id) {
        Object v = db.scalar("SELECT process_name FROM jobs WHERE tenant_id = ? AND id = ?", tenantId, id);
        return v == null ? null : String.valueOf(v);
    }

    public void buat(UUID id, UUID tenantId, String process, String robot, String machine,
                     String source, String priority, String info, String inputJson) {
        db.exec("""
                INSERT INTO jobs
                    (id, tenant_id, process_name, robot_name, machine_name, state, source, priority,
                     progress, info, input_json, created_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, 0, ?, ?, now())
                """, id, tenantId, process, robot, machine, source, priority, info, inputJson);
    }

    /**
     * Ambil satu pekerjaan untuk sebuah robot, dalam SATU langkah tak terbagi.
     *
     * <p>RETURNING mengembalikan baris yang benar-benar diubah, jadi tidak ada
     * kemungkinan salah tebak: mencarinya kembali lewat SELECT terpisah akan
     * memberi baris yang salah begitu satu robot memegang dua pekerjaan.
     *
     * <p>FOR UPDATE SKIP LOCKED menutup sisi satunya: dua robot yang bertanya
     * bersamaan tidak melihat baris yang sama sebagai PENDING, sehingga satu
     * pekerjaan tidak pernah dijalankan dua kali.
     */
    public Map<String, Object> ambilBerikutnya(UUID tenantId, String robot) {
        List<Map<String, Object>> diambil = db.rows("""
                UPDATE jobs
                   SET state = 'RUNNING',
                       robot_name = ?,
                       started_at = now(),
                       info = 'Sedang dijalankan.'
                 WHERE id = (
                       SELECT id FROM jobs
                        WHERE tenant_id = ?
                          AND state = 'PENDING'
                          AND (robot_name IS NULL OR robot_name = '' OR robot_name = ?)
                        ORDER BY CASE priority
                                   WHEN 'High' THEN 0
                                   WHEN 'Normal' THEN 1
                                   ELSE 2
                                 END,
                                 created_at
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED)
             RETURNING id, process_name, robot_name, state, priority, input_json,
                       created_at, started_at
                """, robot, tenantId, robot);

        return diambil.isEmpty() ? null : diambil.get(0);
    }

    /**
     * Perbarui keadaan.
     *
     * <p>COALESCE pada info dan output: medan yang tidak dikirim TIDAK menghapus
     * yang sudah ada. Robot melaporkan kemajuan berkali-kali dan hanya mengisi
     * sebagian medan tiap kali.
     */
    public void ubahKeadaan(UUID tenantId, UUID id, JobState state, int progress,
                            String info, String outputJson) {
        db.exec("""
                UPDATE jobs
                   SET state = ?,
                       progress = ?,
                       info = COALESCE(?, info),
                       output_json = COALESCE(?, output_json),
                       ended_at = CASE WHEN ? THEN now() ELSE ended_at END
                 WHERE tenant_id = ? AND id = ?
                """, state.name(), progress, info, outputJson, state.selesai(), tenantId, id);
    }

    /**
     * Minta berhenti.
     *
     * <p>Yang RUNNING menjadi STOPPING, bukan langsung STOPPED: yang benar-benar
     * bisa menghentikan proses adalah robotnya, dan ia baru tahu pada denyut
     * berikutnya. Yang masih PENDING belum dipegang siapa pun, jadi boleh
     * langsung berhenti.
     */
    public int hentikan(UUID tenantId, UUID id) {
        return db.exec("""
                UPDATE jobs
                   SET state = CASE WHEN state = 'PENDING' THEN 'STOPPED' ELSE 'STOPPING' END,
                       info = 'Diminta berhenti.',
                       ended_at = CASE WHEN state = 'PENDING' THEN now() ELSE ended_at END
                 WHERE tenant_id = ? AND id = ? AND state IN ('PENDING', 'RUNNING')
                """, tenantId, id);
    }

    public int hapus(UUID tenantId, UUID id) {
        return db.exec("DELETE FROM jobs WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    /**
     * Tandai gagal pekerjaan yang robotnya berhenti berdenyut.
     *
     * <p>RETURNING dipakai supaya peringatan hanya dibuat untuk baris yang
     * BENAR-BENAR berubah. Memilih dulu lalu memperbarui membuka celah:
     * pekerjaan yang selesai di antara kedua langkah tetap mendapat peringatan
     * "terputus" padahal berhasil.
     */
    public List<Map<String, Object>> gagalkanYangTerputus(int detik) {
        return db.rows("""
                UPDATE jobs j
                   SET state = 'FAULTED',
                       info = 'Robot ' || COALESCE(j.robot_name, '?')
                              || ' berhenti berdenyut saat pekerjaan masih berjalan.',
                       ended_at = now()
                  FROM (SELECT j2.id
                          FROM jobs j2
                          LEFT JOIN robots r
                                 ON r.tenant_id = j2.tenant_id AND r.name = j2.robot_name
                         WHERE j2.state = 'RUNNING'
                           AND (r.last_heartbeat_at IS NULL
                                OR now() - r.last_heartbeat_at > make_interval(secs => ?))
                         FOR UPDATE OF j2 SKIP LOCKED) AS pilih
                 WHERE j.id = pilih.id AND j.state = 'RUNNING'
             RETURNING j.id, j.tenant_id, j.process_name, j.robot_name
                """, detik);
    }
}
