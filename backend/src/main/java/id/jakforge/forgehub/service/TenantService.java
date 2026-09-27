package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.Timestamps;
import id.jakforge.forgehub.config.ForgeHubProperties;
import id.jakforge.forgehub.dto.response.SettingsResponse;
import id.jakforge.forgehub.repository.DashboardRepository;
import id.jakforge.forgehub.repository.LicenseRepository;
import id.jakforge.forgehub.repository.RobotRepository;
import id.jakforge.forgehub.repository.TenantRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Penyewa, lisensi, dan halaman Setelan. */
@Service
@RequiredArgsConstructor
public class TenantService {

    /**
     * "database", bukan "dataDirectory": datanya ada di PostgreSQL, bukan di
     * sebuah folder. Menyisakan nama medan yang lama akan membuat layar
     * Setelan menampilkan jalur folder yang tidak berarti apa-apa.
     */
    static final String DATABASE_PRODUCT = "PostgreSQL";

    private static final String ATTENDED_PRODUCT = "Attended";
    private static final String UNATTENDED_PRODUCT = "Unattended";

    private final TenantRepository tenantRepository;
    private final LicenseRepository licenseRepository;
    private final RobotRepository robotRepository;
    private final DashboardRepository dashboardRepository;
    private final ForgeHubProperties properties;

    public List<Map<String, Object>> findAllTenants() {
        return tenantRepository.findAll();
    }

    /**
     * Lisensi.
     *
     * <p>"Terpakai" dihitung dari robot yang benar-benar ada, bukan dari angka
     * yang pernah dituliskan seseorang ke kolom {@code used}. Angka yang
     * disimpan akan menyimpang begitu satu robot dihapus tanpa lewat layar ini.
     */
    public List<Map<String, Object>> findLicenses(ForgeHubPrincipal principal) {
        List<Map<String, Object>> licenses = licenseRepository.findAll(principal.tenantId());

        long attendedRobots = robotRepository.countByAttendance(principal.tenantId(), true);
        long unattendedRobots = robotRepository.countByAttendance(principal.tenantId(), false);

        for (Map<String, Object> license : licenses) {
            String product = String.valueOf(license.get("product"));
            boolean attendedLicense = product.contains(ATTENDED_PRODUCT) && !product.contains(UNATTENDED_PRODUCT);

            license.put("used", attendedLicense ? attendedRobots : unattendedRobots);
        }

        return licenses;
    }

    public SettingsResponse getSettings(ForgeHubPrincipal principal) {
        return new SettingsResponse(
                tenantRepository.findName(principal.tenantId()).orElse(null),
                Timestamps.nowText(),
                properties.displayTimezone(),
                properties.robot().heartbeatTimeout().toSeconds(),
                properties.jwt().expiration().toHours(),
                DATABASE_PRODUCT,
                dashboardRepository.countTableRows(principal.tenantId()));
    }
}
