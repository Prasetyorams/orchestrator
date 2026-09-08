package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.Permintaan;
import id.jakforge.forgehub.model.RobotStatus;
import id.jakforge.forgehub.model.Severity;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.repository.InfraRepository;
import id.jakforge.forgehub.repository.LogRepository;
import id.jakforge.forgehub.repository.RobotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Aturan tentang robot, mesin, dan lingkungan. */
@Service
public class RobotService {

    private final RobotRepository robots;
    private final InfraRepository infra;
    private final LogRepository catatan;

    public RobotService(RobotRepository robots, InfraRepository infra, LogRepository catatan) {
        this.robots = robots;
        this.infra = infra;
        this.catatan = catatan;
    }

    // -----------------------------------------------------------------
    // Robot
    // -----------------------------------------------------------------

    public List<Map<String, Object>> daftar(UUID tenantId) {
        return robots.semua(tenantId);
    }

    public Map<String, Object> satu(UUID tenantId, String nama) {
        Map<String, Object> robot = robots.satu(tenantId, nama);

        if (robot == null) throw ApiException.tidakAda("Robot '" + nama + "' tidak ada.");

        return robot;
    }

    /**
     * Denyut dari robot.
     *
     * <p>Robot yang belum dikenal MENDAFTARKAN DIRINYA di sini, bukan ditolak.
     * Memasang JakRunner di mesin baru lalu harus membuka dasbor untuk
     * mendaftarkannya lebih dulu adalah langkah yang selalu terlupakan, dan
     * gejalanya — robot menyala tapi tidak muncul di mana pun — tidak
     * mengarahkan siapa pun ke langkah yang terlupa itu.
     */
    @Transactional
    public Map<String, Object> denyut(UUID tenantId, String nama, Permintaan.Denyut denyut) {
        String status = RobotStatus.dariDenyut(denyut.status()).name();

        if (robots.ada(tenantId, nama)) {
            robots.catatDenyut(tenantId, nama, denyut.machineName(),
                    status, denyut.cpuPercent(), denyut.memoryMb());
        } else {
            robots.daftarkanLewatDenyut(tenantId, nama, denyut.machineName(),
                    status, denyut.cpuPercent(), denyut.memoryMb());

            pastikanMesin(tenantId, denyut.machineName());

            catatan.catatPeringatan(tenantId, Severity.Info, "Robot baru terdaftar",
                    "Robot '" + nama + "' menyambung untuk pertama kali.", "robots");
        }

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("serverTime", Db.nowText());

        return hasil;
    }

    @Transactional
    public void buat(UUID tenantId, Permintaan.Robot minta) {
        if (minta.name() == null) throw ApiException.salah("Nama robot wajib diisi.");

        if (robots.ada(tenantId, minta.name())) {
            throw ApiException.sudahAda("Robot '" + minta.name() + "' sudah ada.");
        }

        robots.buat(tenantId, minta.name(), minta.machineName(), minta.username(),
                minta.type(), minta.environment(), minta.description());

        pastikanMesin(tenantId, minta.machineName());
    }

    public void hapus(UUID tenantId, String nama) {
        if (robots.hapus(tenantId, nama) == 0) {
            throw ApiException.tidakAda("Robot '" + nama + "' tidak ada.");
        }
    }

    // -----------------------------------------------------------------
    // Mesin
    // -----------------------------------------------------------------

    public List<Map<String, Object>> mesin(UUID tenantId) {
        return infra.mesin(tenantId);
    }

    public void buatMesin(UUID tenantId, Permintaan.Mesin minta) {
        if (minta.name() == null) throw ApiException.salah("Nama mesin wajib diisi.");

        if (infra.adaMesin(tenantId, minta.name())) {
            throw ApiException.sudahAda("Mesin '" + minta.name() + "' sudah ada.");
        }

        infra.buatMesin(tenantId, minta.name(), minta.type(), minta.licenseKey(), minta.description());
    }

    public void hapusMesin(UUID tenantId, String nama) {
        if (infra.hapusMesin(tenantId, nama) == 0) {
            throw ApiException.tidakAda("Mesin '" + nama + "' tidak ada.");
        }
    }

    // -----------------------------------------------------------------
    // Lingkungan
    // -----------------------------------------------------------------

    public List<Map<String, Object>> lingkungan(UUID tenantId) {
        return infra.lingkungan(tenantId);
    }

    public void buatLingkungan(UUID tenantId, Permintaan.Bernama minta) {
        if (minta.name() == null) throw ApiException.salah("Nama lingkungan wajib diisi.");

        if (infra.adaLingkungan(tenantId, minta.name())) {
            throw ApiException.sudahAda("Lingkungan '" + minta.name() + "' sudah ada.");
        }

        infra.buatLingkungan(tenantId, minta.name(), minta.description());
    }

    public void hapusLingkungan(UUID tenantId, String nama) {
        if (infra.hapusLingkungan(tenantId, nama) == 0) {
            throw ApiException.tidakAda("Lingkungan '" + nama + "' tidak ada.");
        }
    }

    /**
     * Mesin yang disebut robot didaftarkan kalau belum ada.
     *
     * <p>Tanpa ini, halaman Machines kosong sementara halaman Robots penuh —
     * dan keduanya benar menurut datanya masing-masing, yang justru membuat
     * kejanggalannya sulit dijelaskan.
     */
    private void pastikanMesin(UUID tenantId, String mesin) {
        if (mesin == null || mesin.isBlank()) return;
        if (infra.adaMesin(tenantId, mesin)) return;

        infra.buatMesin(tenantId, mesin, "Standard", null, "Terdaftar sendiri lewat denyut robot.");
    }
}
