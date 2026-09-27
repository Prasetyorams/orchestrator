package id.jakforge.forgehub.service;

import id.jakforge.forgehub.config.ForgeHubProperties;
import id.jakforge.forgehub.model.MachineTypes;
import id.jakforge.forgehub.model.RoleNames;
import id.jakforge.forgehub.repository.MachineRepository;
import id.jakforge.forgehub.repository.TenantRepository;
import id.jakforge.forgehub.repository.UserRepository;
import id.jakforge.forgehub.security.Passwords;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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
 *
 * <p>Nilainya dari {@code forgehub.bootstrap.*}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InitialDataSeeder implements ApplicationRunner {

    /** Nama mesin kalau nama host pun tidak bisa dibaca. */
    static final String FALLBACK_MACHINE_NAME = "forgehub";

    private static final String LOCAL_MACHINE_DESCRIPTION = "Mesin tempat ForgeHub berjalan.";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final MachineRepository machineRepository;
    private final ForgeHubProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        ForgeHubProperties.Bootstrap bootstrap = properties.bootstrap();

        // Migrasi Flyway selalu menanam penyewanya, jadi yang tidak ada berarti
        // skemanya bukan yang diharapkan. Berhenti di sini lebih baik daripada
        // berjalan dengan penyewa null yang menjadikan setiap kueri kosong.
        UUID tenantId = tenantRepository.findIdByName(bootstrap.tenantName())
                .orElseThrow(() -> new IllegalStateException("Penyewa \"" + bootstrap.tenantName()
                        + "\" tidak ada. Migrasi Flyway tidak berjalan sebagaimana mestinya."));

        seedAdministrator(tenantId, bootstrap);
        seedLocalMachine(tenantId, bootstrap);
    }

    private void seedAdministrator(UUID tenantId, ForgeHubProperties.Bootstrap bootstrap) {
        if (userRepository.existsByUsername(tenantId, bootstrap.adminUsername())) return;

        userRepository.insert(tenantId, bootstrap.adminUsername(), Passwords.hash(bootstrap.adminPassword()),
                bootstrap.adminDisplayName(), null, RoleNames.ADMINISTRATOR, true);

        // Kata sandinya tidak ikut dicatat: nilai itu bisa diganti lewat
        // variabel lingkungan, dan catatan server dibaca lebih banyak orang
        // daripada yang seharusnya tahu kata sandi Administrator.
        log.info("Pengguna pertama dibuat: {} — GANTI kata sandi bawaannya.", bootstrap.adminUsername());
    }

    /**
     * Nama mesin di dalam container adalah nama CONTAINER, bukan nama komputer
     * yang sebenarnya. Itu tidak apa-apa: yang penting barisnya ada supaya
     * denyut robot punya tempat mendarat, dan robot mengirimkan nama mesinnya
     * sendiri saat berdenyut.
     */
    private void seedLocalMachine(UUID tenantId, ForgeHubProperties.Bootstrap bootstrap) {
        String machineName = resolveMachineName(bootstrap.machineName());

        if (machineRepository.existsByName(tenantId, machineName)) return;

        machineRepository.insert(tenantId, machineName, MachineTypes.STANDARD, null, LOCAL_MACHINE_DESCRIPTION);
    }

    private static String resolveMachineName(String configuredName) {
        if (configuredName != null && !configuredName.isBlank()) return configuredName.trim();

        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return FALLBACK_MACHINE_NAME;
        }
    }
}
