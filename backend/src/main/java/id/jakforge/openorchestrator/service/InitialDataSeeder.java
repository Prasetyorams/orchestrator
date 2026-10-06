package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.model.MachineTypes;
import id.jakforge.openorchestrator.model.RoleNames;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.repository.TenantRepository;
import id.jakforge.openorchestrator.repository.UserRepository;
import id.jakforge.openorchestrator.security.Passwords;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *       OpenOrchestrator di dunia memakai garam yang sama, dan satu tabel pelangi cukup
 *       untuk semuanya.</li>
 *   <li><b>Mesin tempat OpenOrchestrator berjalan.</b> Namanya baru diketahui saat
 *       dijalankan. Tanpa barisnya, denyut pertama dari JakRunner tiba untuk
 *       mesin yang belum dikenal dan halaman Machines kosong padahal jelas ada
 *       satu yang aktif. Di dalam container hanya kalau namanya disetel —
 *       lihat {@link #seedLocalMachine}.</li>
 * </ol>
 *
 * <p>Keduanya diperiksa dulu, bukan disisipkan buta. Ini berjalan setiap kali
 * aplikasi naik, termasuk pada basis data yang sudah berisi data pindahan dari
 * SQLite — dan pengguna yang sudah ada di sana TIDAK boleh tertimpa kata sandi
 * bawaan.
 *
 * <p>Nilainya dari {@code openorchestrator.bootstrap.*}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InitialDataSeeder implements ApplicationRunner {

    /** Nama mesin kalau nama host pun tidak bisa dibaca. */
    static final String FALLBACK_MACHINE_NAME = "openorchestrator";

    private static final String LOCAL_MACHINE_DESCRIPTION = "Mesin tempat OpenOrchestrator berjalan.";

    /** Penanda yang dibuat Docker dan Podman di akar setiap container. */
    private static final Path[] CONTAINER_MARKERS = {Path.of("/.dockerenv"), Path.of("/run/.containerenv")};

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final MachineRepository machineRepository;
    private final OpenOrchestratorProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        OpenOrchestratorProperties.Bootstrap bootstrap = properties.bootstrap();

        // Migrasi Flyway selalu menanam penyewanya, jadi yang tidak ada berarti
        // skemanya bukan yang diharapkan. Berhenti di sini lebih baik daripada
        // berjalan dengan penyewa null yang menjadikan setiap kueri kosong.
        UUID tenantId = tenantRepository.findIdByName(bootstrap.tenantName())
                .orElseThrow(() -> new IllegalStateException("Penyewa \"" + bootstrap.tenantName()
                        + "\" tidak ada. Migrasi Flyway tidak berjalan sebagaimana mestinya."));

        seedAdministrator(tenantId, bootstrap);
        seedLocalMachine(tenantId, bootstrap);
    }

    private void seedAdministrator(UUID tenantId, OpenOrchestratorProperties.Bootstrap bootstrap) {
        if (userRepository.existsByUsername(tenantId, bootstrap.adminUsername())) return;

        userRepository.insert(tenantId, bootstrap.adminUsername(), Passwords.hash(bootstrap.adminPassword()),
                bootstrap.adminDisplayName(), null, RoleNames.ADMINISTRATOR, true);

        // Kata sandinya tidak ikut dicatat: nilai itu bisa diganti lewat
        // variabel lingkungan, dan catatan server dibaca lebih banyak orang
        // daripada yang seharusnya tahu kata sandi Administrator.
        log.info("Pengguna pertama dibuat: {} — GANTI kata sandi bawaannya.", bootstrap.adminUsername());
    }

    /**
     * Mesin bernama host komputer ini, supaya JakRunner di komputer yang sama
     * punya tempat mendarat.
     *
     * <p>Di dalam container nama host adalah nama CONTAINER: acak, dan baru
     * setiap kali container dibuat ulang. Robot mana pun mengirim nama
     * mesinnya sendiri, jadi baris itu tidak pernah dipakai — hanya menumpuk,
     * satu per deploy, dan sejak V12 setiap mesin baru masuk folder bawaan
     * sebagai pilihan Start Job yang selalu offline. Di container hanya nama
     * yang disetel ({@code OPENORCHESTRATOR_MACHINE_NAME}) yang ditanam.
     */
    private void seedLocalMachine(UUID tenantId, OpenOrchestratorProperties.Bootstrap bootstrap) {
        String configuredName = bootstrap.machineName();
        boolean named = configuredName != null && !configuredName.isBlank();

        if (!named && runningInContainer()) return;

        String machineName = named ? configuredName.trim() : hostName();

        if (machineRepository.existsByName(tenantId, machineName)) return;

        machineRepository.insert(tenantId, machineName, MachineTypes.STANDARD, null, LOCAL_MACHINE_DESCRIPTION);
    }

    private static boolean runningInContainer() {
        for (Path marker : CONTAINER_MARKERS) {
            if (Files.exists(marker)) return true;
        }

        return false;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return FALLBACK_MACHINE_NAME;
        }
    }
}
