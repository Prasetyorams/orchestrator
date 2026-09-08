package id.jakforge.forgehub.repository;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mesin dan lingkungan: dua tabel yang bentuknya hampir sama.
 *
 * <p>Digabung dalam satu repository dengan sengaja. Keduanya cuma nama,
 * keterangan, dan hitungan robot; dua berkas terpisah berisi enam metode yang
 * hampir identik akan berbeda satu sama lain begitu salah satunya disunting.
 */
@Repository
public class InfraRepository {

    private final Db db;

    public InfraRepository(Db db) {
        this.db = db;
    }

    // -----------------------------------------------------------------
    // Mesin
    // -----------------------------------------------------------------

    public List<Map<String, Object>> mesin(UUID tenantId) {
        return db.rows("""
                SELECT m.id, m.name, m.type, m.license_key, m.description, m.created_at,
                       (SELECT count(*) FROM robots r
                         WHERE r.tenant_id = m.tenant_id AND r.machine_name = m.name) AS robot_count
                  FROM machines m
                 WHERE m.tenant_id = ?
                 ORDER BY m.name
                """, tenantId);
    }

    public boolean adaMesin(UUID tenantId, String nama) {
        return db.exists("SELECT count(*) FROM machines WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    public void buatMesin(UUID tenantId, String nama, String tipe, String kunci, String keterangan) {
        db.exec("""
                INSERT INTO machines (id, tenant_id, name, type, license_key, description, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, Db.newId(), tenantId, nama, tipe, kunci, keterangan);
    }

    public int hapusMesin(UUID tenantId, String nama) {
        return db.exec("DELETE FROM machines WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }

    // -----------------------------------------------------------------
    // Lingkungan
    // -----------------------------------------------------------------

    public List<Map<String, Object>> lingkungan(UUID tenantId) {
        return db.rows("""
                SELECT e.id, e.name, e.description, e.created_at,
                       (SELECT count(*) FROM robots r
                         WHERE r.tenant_id = e.tenant_id AND r.environment = e.name) AS robot_count
                  FROM environments e
                 WHERE e.tenant_id = ?
                 ORDER BY e.name
                """, tenantId);
    }

    public boolean adaLingkungan(UUID tenantId, String nama) {
        return db.exists("SELECT count(*) FROM environments WHERE tenant_id = ? AND name = ?",
                tenantId, nama);
    }

    public void buatLingkungan(UUID tenantId, String nama, String keterangan) {
        db.exec("""
                INSERT INTO environments (id, tenant_id, name, description, created_at)
                VALUES (?, ?, ?, ?, now())
                """, Db.newId(), tenantId, nama, keterangan);
    }

    public int hapusLingkungan(UUID tenantId, String nama) {
        return db.exec("DELETE FROM environments WHERE tenant_id = ? AND name = ?", tenantId, nama);
    }
}
