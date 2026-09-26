package id.jakforge.forgehub.config;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.Izin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;

/**
 * Memeriksa izin peran untuk SETIAP endpoint /api, sebelum controllernya
 * berjalan.
 *
 * <p>Satu tabel untuk semuanya, bukan satu baris "periksa izin" di setiap
 * controller: baris seperti itu pasti terlupa pada endpoint berikutnya, dan
 * yang terlupa terbuka untuk siapa pun yang sudah masuk. Tabelnya juga yang
 * dibaca orang yang ingin tahu "peran Robot butuh izin apa saja".
 *
 * <p>Endpoint yang TIDAK ada di tabel DITOLAK, bukan dibiarkan terbuka. Uji
 * {@code IzinInterceptorTest} memastikan setiap endpoint punya barisnya, jadi
 * endpoint baru yang lupa didaftarkan ketahuan saat uji, bukan saat seseorang
 * yang tidak berhak ternyata bisa memanggilnya.
 *
 * <p>Endpoint "simpan" yang membuat atau mengubah (POST /api/assets, ...)
 * cukup meminta salah satunya di sini; layanannya yang memutuskan mana yang
 * benar-benar diperlukan, sesudah tahu apakah namanya sudah ada (lihat
 * {@link id.jakforge.forgehub.security.Penjaga}).
 */
@Component
public class IzinInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(IzinInterceptor.class);

    /** Tanpa token sama sekali. */
    static final String PUBLIK = "publik";

    /** Siapa pun yang sudah masuk. */
    static final String MASUK = "masuk";

    /**
     * @param izin PUBLIK, MASUK, satu izin, atau beberapa dipisah "|" yang
     *             cukup SALAH SATU-nya.
     */
    record Aturan(String metode, String pola, String izin) {
    }

    private static Aturan a(String metode, String pola, String izin) {
        return new Aturan(metode, pola, izin);
    }

    static final List<Aturan> ATURAN = List.of(
            // --- sesi dan profil ---
            a("GET", "/api/health", PUBLIK),
            a("POST", "/api/auth/login", PUBLIK),
            a("GET", "/api/auth/me", MASUK),
            a("PUT", "/api/auth/me", MASUK),
            a("POST", "/api/auth/password", MASUK),

            // --- beranda dan pencarian: isinya sudah disaring folder dan izin ---
            a("GET", "/api/dashboard", MASUK),
            a("GET", "/api/dashboard/history", MASUK),
            a("GET", "/api/search", MASUK),

            // --- folder ---
            a("GET", "/api/folders", MASUK),
            a("GET", "/api/folders/manage", "folders.read"),
            a("POST", "/api/folders", "folders.create"),
            a("POST", "/api/folders/personal", MASUK),
            a("PUT", "/api/folders/{id}", "folders.update"),
            a("DELETE", "/api/folders/{id}", "folders.delete"),
            a("GET", "/api/folders/{id}/members", MASUK),
            a("POST", "/api/folders/{id}/users", "folders.update"),
            a("DELETE", "/api/folders/{id}/users/{username}", "folders.update"),
            // Robot Folder Saya diatur pemiliknya sendiri; FolderService yang memutuskan.
            a("POST", "/api/folders/{id}/robots", MASUK),
            a("DELETE", "/api/folders/{id}/robots/{name}", MASUK),

            // --- proses dan paket ---
            a("GET", "/api/processes", "processes.read"),
            a("POST", "/api/processes", "processes.create|processes.update"),
            a("PUT", "/api/processes/{name}/folder", "processes.update"),
            a("DELETE", "/api/processes/{name}", "processes.delete"),
            a("GET", "/api/packages", "packages.read"),
            a("POST", "/api/packages", "packages.create|packages.update"),
            a("GET", "/api/packages/{name}/{version}/content", "packages.read"),
            a("DELETE", "/api/packages/{name}/{version}", "packages.delete"),

            // --- pekerjaan: mengambil dan melaporkan pekerjaan adalah tugas robot ---
            a("GET", "/api/jobs", "jobs.read"),
            a("GET", "/api/jobs/next", "jobs.update"),
            a("GET", "/api/jobs/{id}", "jobs.read"),
            a("POST", "/api/jobs", "jobs.create"),
            a("POST", "/api/jobs/{id}/state", "jobs.update"),
            a("POST", "/api/jobs/{id}/stop", "jobs.update"),
            a("DELETE", "/api/jobs/{id}", "jobs.delete"),

            // --- pemicu ---
            a("GET", "/api/triggers", "triggers.read"),
            a("POST", "/api/triggers", "triggers.create|triggers.update"),
            a("POST", "/api/triggers/{name}/toggle", "triggers.update"),
            a("DELETE", "/api/triggers/{name}", "triggers.delete"),

            // --- antrean: menambah, mengambil, dan melaporkan butir mengubah isi antreannya ---
            a("GET", "/api/queues", "queues.read"),
            a("POST", "/api/queues", "queues.create"),
            a("PUT", "/api/queues/{name}/folder", "queues.update"),
            a("DELETE", "/api/queues/{name}", "queues.delete"),
            a("GET", "/api/queues/{name}/items", "queues.read"),
            a("POST", "/api/queues/{name}/items", "queues.update"),
            a("POST", "/api/queues/{name}/next", "queues.update"),
            a("POST", "/api/queues/items/{id}/result", "queues.update"),
            a("DELETE", "/api/queues/items/{id}", "queues.delete"),

            // --- aset, termasuk kredensial ---
            a("GET", "/api/assets", "assets.read"),
            a("POST", "/api/assets", "assets.create|assets.update"),
            a("PUT", "/api/assets/{name}/folder", "assets.update"),
            a("GET", "/api/assets/{name}/value", "assets.read"),
            a("DELETE", "/api/assets/{name}", "assets.delete"),
            a("GET", "/api/credentials", "assets.read"),
            a("POST", "/api/credentials", "assets.create|assets.update"),
            a("GET", "/api/credentials/{name}/value", "assets.read"),
            a("DELETE", "/api/credentials/{name}", "assets.delete"),

            // --- ember penyimpanan ---
            a("GET", "/api/buckets", "buckets.read"),
            a("POST", "/api/buckets", "buckets.create"),
            a("PUT", "/api/buckets/{name}/folder", "buckets.update"),
            a("DELETE", "/api/buckets/{name}", "buckets.delete"),
            a("GET", "/api/buckets/{name}/files", "buckets.read"),
            a("POST", "/api/buckets/{name}/files", "buckets.update"),
            a("GET", "/api/buckets/{name}/files/{id}/content", "buckets.read"),
            a("DELETE", "/api/buckets/{name}/files/{id}", "buckets.delete"),

            // --- robot, mesin, lingkungan: denyut adalah robots.update ---
            a("GET", "/api/robots", "robots.read"),
            a("GET", "/api/robots/{name}", "robots.read"),
            a("POST", "/api/robots/{name}/heartbeat", "robots.update"),
            a("POST", "/api/robots", "robots.create"),
            a("DELETE", "/api/robots/{name}", "robots.delete"),
            a("GET", "/api/machines", "machines.read"),
            a("POST", "/api/machines", "machines.create"),
            a("DELETE", "/api/machines/{name}", "machines.delete"),
            a("GET", "/api/environments", "environments.read"),
            a("POST", "/api/environments", "environments.create"),
            a("DELETE", "/api/environments/{name}", "environments.delete"),

            // --- catatan dan peringatan ---
            a("GET", "/api/logs", "logs.read"),
            a("POST", "/api/logs", "logs.create"),
            a("DELETE", "/api/logs", "logs.delete"),
            a("GET", "/api/alerts", "alerts.read"),
            a("GET", "/api/alerts/summary", "alerts.read"),
            a("POST", "/api/alerts/{id}/read", "alerts.update"),
            a("POST", "/api/alerts/read-all", "alerts.update"),

            // --- pengguna dan peran: daftar peran juga dibaca layar Pengguna ---
            a("GET", "/api/users", "users.read"),
            a("POST", "/api/users", "users.create|users.update"),
            a("DELETE", "/api/users/{username}", "users.delete"),
            a("GET", "/api/roles", "roles.read|users.read"),
            a("GET", "/api/permissions", "roles.read"),
            a("POST", "/api/roles", "roles.create"),
            a("PUT", "/api/roles/{name}", "roles.update"),
            a("DELETE", "/api/roles/{name}", "roles.delete"),

            // --- penyewa, lisensi, setelan, audit ---
            a("GET", "/api/tenants", "settings.read"),
            a("GET", "/api/licensing", "settings.read"),
            a("GET", "/api/settings", "settings.read"),
            a("GET", "/api/audit", "audit.read"),
            a("GET", "/api/audit/components", "audit.read"));

    private final Izin izin;

    public IzinInterceptor(Izin izin) {
        this.izin = izin;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        // Hanya endpoint controller. Yang lain — berkas statis, halaman galat
        // bawaan — tidak punya aturan dan tidak perlu.
        if (!(handler instanceof HandlerMethod)) return true;

        Object pola = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Aturan aturan = pola == null ? null : aturanUntuk(request.getMethod(), pola.toString());

        if (aturan == null) {
            log.error("Endpoint {} {} tidak punya aturan izin; ditolak.", request.getMethod(), pola);
            throw ApiException.tidakBerhak("Endpoint ini belum punya aturan izin.");
        }

        if (PUBLIK.equals(aturan.izin())) return true;

        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof ForgeHubPrincipal p)) {
            throw ApiException.belumMasuk("Token tidak sah atau sudah kedaluwarsa.");
        }

        if (MASUK.equals(aturan.izin())) return true;

        String[] pilihan = aturan.izin().split("\\|");

        for (String x : pilihan) {
            if (izin.boleh(p, x)) return true;
        }

        throw Izin.tolak(pilihan[0]);
    }

    static Aturan aturanUntuk(String metode, String pola) {
        for (Aturan a : ATURAN) {
            if (a.metode().equals(metode) && a.pola().equals(pola)) return a;
        }

        return null;
    }
}
