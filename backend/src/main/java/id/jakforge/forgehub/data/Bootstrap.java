package id.jakforge.forgehub.data;

import id.jakforge.forgehub.security.Passwords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Isi awal yang tidak bisa ditulis di berkas migrasi.
 *
 * <p>Flyway V2 sudah menanam yang tetap: penyewa, peran, lingkungan, antrean,
 * bucket, dan lisensi. Dua hal tersisa karena keduanya butuh nilai yang baru
 * diketahui saat program berjalan:
 *
 * <ol>
 *   <li><b>Pengguna pertama.</b> Hash kata sandinya memakai garam acak. Garam
 *       yang dituliskan tetap di dalam berkas migrasi berarti setiap pemasangan
 *       ForgeHub di dunia memakai garam yang sama, dan satu tabel pelangi cukup
 *       untuk semuanya.</li>
 *   <li><b>Mesin tempat ForgeHub berjalan.</b> Namanya baru diketahui saat
 *       dijalankan. Tanpa barisnya, denyut pertama dari JakRunner tiba untuk
 *       mesin yang belum dikenal dan halaman Machines kosong padahal jelas ada
 *       satu yang aktif.</li>
 * </ol>
 *
 * <p>Keduanya diperiksa dulu, bukan disisipkan buta. Ini berjalan setiap kali
 * aplikasi naik, termasuk pada basis data yang sudah berisi data pindahan dari
 * SQLite — dan pengguna yang sudah ada di sana TIDAK boleh tertimpa kata sandi
 * bawaan.
 */
@Component
public class Bootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    public static final String TENANT_BAWAAN = "default";
    public static final String PENGGUNA_BAWAAN = "FH_Admin";
    public static final String SANDI_BAWAAN = "forgehub";

    private final Db db;

    public Bootstrap(Db db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID tenantId = tenantBawaan();

        if (tenantId == null) {
            // Migrasi Flyway selalu menanamnya, jadi ini berarti skemanya bukan
            // yang diharapkan. Berhenti di sini lebih baik daripada berjalan
            // dengan tenant null yang menjadikan setiap kueri kosong.
            throw new IllegalStateException(
                    "Penyewa \"" + TENANT_BAWAAN + "\" tidak ada. Migrasi Flyway tidak berjalan sebagaimana mestinya.");
        }

        tanamPengguna(tenantId);
        tanamMesinIni(tenantId);
    }

    public UUID tenantBawaan() {
        Object id = db.scalar("SELECT id FROM tenants WHERE name = ?", TENANT_BAWAAN);
        return id instanceof UUID u ? u : Db.uuid(String.valueOf(id));
    }

    private void tanamPengguna(UUID tenantId) {
        if (db.exists("SELECT count(*) FROM users WHERE tenant_id = ? AND username = ?",
                tenantId, PENGGUNA_BAWAAN)) {
            return;
        }

        db.exec("""
                INSERT INTO users (id, tenant_id, username, password_hash, display_name,
                                   email, role, is_active, created_at)
                VALUES (?, ?, ?, ?, ?, NULL, 'Administrator', TRUE, now())
                """,
                Db.newId(), tenantId, PENGGUNA_BAWAAN,
                Passwords.hash(SANDI_BAWAAN), "ForgeHub Administrator");

        log.info("Pengguna pertama dibuat: {} / {} — GANTI kata sandinya.",
                PENGGUNA_BAWAAN, SANDI_BAWAAN);
    }

    /**
     * Nama mesin di dalam container adalah nama CONTAINER, bukan nama komputer
     * yang sebenarnya. Itu tidak apa-apa: yang penting barisnya ada supaya
     * denyut robot punya tempat mendarat, dan robot mengirimkan nama mesinnya
     * sendiri saat berdenyut.
     */
    private void tanamMesinIni(UUID tenantId) {
        String nama = namaMesin();

        if (db.exists("SELECT count(*) FROM machines WHERE tenant_id = ? AND name = ?", tenantId, nama)) {
            return;
        }

        db.exec("""
                INSERT INTO machines (id, tenant_id, name, type, description, created_at)
                VALUES (?, ?, ?, 'Standard', 'Mesin tempat ForgeHub berjalan.', now())
                """,
                Db.newId(), tenantId, nama);
    }

    private static String namaMesin() {
        String dariLingkungan = System.getenv("FORGEHUB_MACHINE_NAME");
        if (dariLingkungan != null && !dariLingkungan.isBlank()) return dariLingkungan.trim();

        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "forgehub";
        }
    }
}
