package id.jakforge.forgehub.repository;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Akses data robot.
 *
 * <p>Status DIHITUNG saat dibaca, bukan diambil dari kolomnya. Robot yang mati
 * tidak sempat memberi tahu bahwa ia mati — itulah artinya mati. Kolom status
 * yang hanya diperbarui oleh robotnya sendiri akan selamanya berbunyi AVAILABLE
 * untuk mesin yang sudah dimatikan seminggu lalu.
 */
@Repository
public class RobotRepository {

    /** Setelah berapa lama tanpa denyut sebuah robot dianggap putus. */
    public static final int PUTUS_SETELAH_DETIK = 45;

    private static final String STATUS = """
            CASE
                WHEN last_heartbeat_at IS NULL THEN 'DISCONNECTED'
                WHEN now() - last_heartbeat_at > make_interval(secs => %d) THEN 'DISCONNECTED'
                ELSE status
            END AS status
            """.formatted(PUTUS_SETELAH_DETIK);

    private final Db db;

    public RobotRepository(Db db) {
        this.db = db;
    }

    /**
     * @param folderId null berarti semua robot penyewa; selain itu hanya robot
     *                 yang ditugaskan ke folder itu.
     *
     * <p>{@code folders} berisi nama folder bersama tempat robotnya ditugaskan,
     * supaya halaman robot penyewa bisa menunjukkannya tanpa bertanya satu per
     * satu. Folder Saya tidak ikut disebut: namanya sama untuk semua orang dan
     * tidak menjelaskan apa pun.
     */
    public List<Map<String, Object>> semua(UUID tenantId, UUID folderId) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String saring = "";

        if (folderId != null) {
            saring = " AND id IN (SELECT robot_id FROM folder_robots WHERE tenant_id = ? AND folder_id = ?)";
            args.add(tenantId);
            args.add(folderId);
        }

        return db.rows("""
                SELECT id, name, machine_name, username, type, environment, description,
                       %s, cpu_percent, memory_mb, last_heartbeat_at, created_at,
                       (SELECT array_agg(f.name ORDER BY lower(f.name))
                          FROM folder_robots fr JOIN folders f ON f.id = fr.folder_id
                         WHERE fr.robot_id = robots.id AND f.owner_id IS NULL) AS folders
                  FROM robots
                 WHERE tenant_id = ?%s
                 ORDER BY name
                """.formatted(STATUS, saring), args.toArray());
    }

    public Map<String, Object> satu(UUID tenantId, String nama) {
        return db.row("""
                SELECT id, name, machine_name, username, type, environment, description,
                       %s, cpu_percent, memory_mb, last_heartbeat_at, created_at
                  FROM robots
                 WHERE tenant_id = ? AND name = ?
                """.formatted(STATUS), tenantId, nama);
    }

    /** Untuk dasbor: ikut membawa jumlah pekerjaan yang sedang dijalankannya. */
    public List<Map<String, Object>> untukDasbor(UUID tenantId, UUID folderId, int batas) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String saring = "";

        if (folderId != null) {
            saring = " AND r.id IN (SELECT robot_id FROM folder_robots WHERE tenant_id = ? AND folder_id = ?)";
            args.add(tenantId);
            args.add(folderId);
        }

        args.add(batas);

        return db.rows("""
                SELECT r.name, r.machine_name, r.type, r.environment, r.cpu_percent, r.memory_mb,
                       r.last_heartbeat_at,
                       CASE WHEN r.last_heartbeat_at IS NULL
                              OR now() - r.last_heartbeat_at > make_interval(secs => %d)
                            THEN 'DISCONNECTED' ELSE r.status END AS status,
                       (SELECT count(*) FROM jobs j
                         WHERE j.tenant_id = r.tenant_id AND j.robot_name = r.name
                           AND j.state = 'RUNNING') AS running_jobs
                  FROM robots r
                 WHERE r.tenant_id = ?%s
                 ORDER BY CASE WHEN r.last_heartbeat_at IS NULL
                                 OR now() - r.last_heartbeat_at > make_interval(secs => %d)
                               THEN 2 ELSE 0 END,
                          r.name
                 LIMIT ?
                """.formatted(PUTUS_SETELAH_DETIK, saring, PUTUS_SETELAH_DETIK), args.toArray());
    }

    public boolean ada(UUID tenantId, String nama) {
        return db.exists("SELECT count(*) FROM robots WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    public void daftarkanLewatDenyut(UUID tenantId, String nama, String mesin,
                                     String status, double cpu, double memori) {
        db.exec("""
                INSERT INTO robots
                    (id, tenant_id, name, machine_name, type, environment, description,
                     status, cpu_percent, memory_mb, last_heartbeat_at, created_at)
                VALUES (?, ?, ?, ?, 'Attended', 'Production',
                        'Terdaftar sendiri saat denyut pertama.', ?, ?, ?, now(), now())
                """, Db.newId(), tenantId, nama, mesin, status, cpu, memori);
    }

    /**
     * COALESCE pada machine_name: denyut yang tidak menyebut nama mesin tidak
     * boleh MENGHAPUS nama yang sudah diketahui.
     */
    public void catatDenyut(UUID tenantId, String nama, String mesin,
                            String status, double cpu, double memori) {
        db.exec("""
                UPDATE robots
                   SET status = ?, cpu_percent = ?, memory_mb = ?,
                       last_heartbeat_at = now(),
                       machine_name = COALESCE(?, machine_name)
                 WHERE tenant_id = ? AND name = ?
                """, status, cpu, memori, mesin, tenantId, nama);
    }

    public void buat(UUID tenantId, String nama, String mesin, String pengguna,
                     String tipe, String lingkungan, String keterangan) {
        db.exec("""
                INSERT INTO robots
                    (id, tenant_id, name, machine_name, username, type, environment, description,
                     status, cpu_percent, memory_mb, last_heartbeat_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DISCONNECTED', 0, 0, NULL, now())
                """, Db.newId(), tenantId, nama, mesin, pengguna, tipe, lingkungan, keterangan);
    }

    public int hapus(UUID tenantId, String nama) {
        return db.exec("DELETE FROM robots WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    /** Hitungan per keadaan, diambil dalam satu kueri supaya angkanya sezaman. */
    public Map<String, Object> hitungan(UUID tenantId, UUID folderId) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String saring = "";

        if (folderId != null) {
            saring = " AND id IN (SELECT robot_id FROM folder_robots WHERE tenant_id = ? AND folder_id = ?)";
            args.add(tenantId);
            args.add(folderId);
        }

        return db.row("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'AVAILABLE' AND segar) AS available,
                       count(*) FILTER (WHERE status = 'BUSY'      AND segar) AS busy,
                       count(*) FILTER (WHERE NOT segar)                      AS disconnected
                  FROM (SELECT status,
                               last_heartbeat_at IS NOT NULL
                               AND now() - last_heartbeat_at <= make_interval(secs => %d) AS segar
                          FROM robots WHERE tenant_id = ?%s) AS r
                """.formatted(PUTUS_SETELAH_DETIK, saring), args.toArray());
    }

    /** Jumlah robot Attended, atau selain Attended. Dipakai halaman lisensi. */
    public long hitungTipe(UUID tenantId, boolean attended) {
        String banding = attended ? "=" : "<>";

        return db.count("SELECT count(*) FROM robots WHERE tenant_id = ? AND type " + banding + " 'Attended'",
                tenantId);
    }
}
