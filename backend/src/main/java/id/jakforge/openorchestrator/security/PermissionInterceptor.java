package id.jakforge.openorchestrator.security;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.service.AgentAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

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
 * {@code PermissionInterceptorTest} memastikan setiap endpoint punya barisnya,
 * jadi endpoint baru yang lupa didaftarkan ketahuan saat uji, bukan saat
 * seseorang yang tidak berhak ternyata bisa memanggilnya.
 *
 * <p>Endpoint "simpan" yang membuat atau mengubah (POST /api/assets, ...)
 * cukup meminta salah satunya di sini; layanannya yang memutuskan mana yang
 * benar-benar diperlukan, sesudah tahu apakah namanya sudah ada (lihat
 * {@link PermissionChecker#requireSave}).
 *
 * <p>Token Robot Agent hanya berlaku di endpoint {@link Access#AGENT}, ditambah
 * unduhan paket yang sedang dijalankan robotnya. Token executor diperlakukan
 * seperti peran robot yang sempit, dan berhenti berlaku begitu job-nya selesai.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    /** Siapa yang boleh memanggil sebuah endpoint. */
    enum Access {
        /** Tanpa token sama sekali. */
        PUBLIC,
        /** Siapa pun yang sudah masuk; isinya disaring layanannya. */
        AUTHENTICATED,
        /** Peran yang punya SALAH SATU izin di {@code anyOfPermissions}. */
        PERMISSION,
        /** Hanya Robot Agent, dengan token dari machine key. */
        AGENT
    }

    record PermissionRule(HttpMethod method, String pathPattern, Access access, List<String> anyOfPermissions) {
    }

    private static PermissionRule open(HttpMethod method, String pathPattern) {
        return new PermissionRule(method, pathPattern, Access.PUBLIC, List.of());
    }

    private static PermissionRule signedIn(HttpMethod method, String pathPattern) {
        return new PermissionRule(method, pathPattern, Access.AUTHENTICATED, List.of());
    }

    private static PermissionRule requires(HttpMethod method, String pathPattern, String... anyOfPermissions) {
        return new PermissionRule(method, pathPattern, Access.PERMISSION, List.of(anyOfPermissions));
    }

    private static PermissionRule agent(HttpMethod method, String pathPattern) {
        return new PermissionRule(method, pathPattern, Access.AGENT, List.of());
    }

    /** Satu-satunya endpoint di luar /api/agent yang boleh dipanggil token agent. */
    static final String PACKAGE_CONTENT_PATH = "/api/packages/{name}/{version}/content";

    static final List<PermissionRule> RULES = List.of(
            // --- sesi dan profil ---
            open(GET, "/api/health"),
            open(POST, "/api/auth/login"),
            signedIn(GET, "/api/auth/me"),
            signedIn(PUT, "/api/auth/me"),
            signedIn(POST, "/api/auth/password"),

            // --- beranda dan pencarian: isinya sudah disaring folder dan izin ---
            signedIn(GET, "/api/dashboard"),
            signedIn(GET, "/api/dashboard/history"),
            signedIn(GET, "/api/search"),

            // --- folder ---
            signedIn(GET, "/api/folders"),
            requires(GET, "/api/folders/manage", "folders.read"),
            requires(POST, "/api/folders", "folders.create"),
            signedIn(POST, "/api/folders/personal"),
            requires(PUT, "/api/folders/{id}", "folders.update"),
            requires(DELETE, "/api/folders/{id}", "folders.delete"),
            signedIn(GET, "/api/folders/{id}/members"),
            requires(POST, "/api/folders/{id}/users", "folders.update"),
            requires(DELETE, "/api/folders/{id}/users/{username}", "folders.update"),
            // Robot Folder Saya diatur pemiliknya sendiri; FolderService yang memutuskan.
            signedIn(POST, "/api/folders/{id}/robots"),
            signedIn(DELETE, "/api/folders/{id}/robots/{name}"),

            // --- proses dan paket ---
            requires(GET, "/api/processes", "processes.read"),
            requires(POST, "/api/processes", "processes.create", "processes.update"),
            requires(PUT, "/api/processes/{name}/folder", "processes.update"),
            requires(DELETE, "/api/processes/{name}", "processes.delete"),
            requires(GET, "/api/packages", "packages.read"),
            requires(POST, "/api/packages", "packages.create", "packages.update"),
            requires(GET, PACKAGE_CONTENT_PATH, "packages.read"),
            requires(DELETE, "/api/packages/{name}/{version}", "packages.delete"),

            // --- pekerjaan: mengambil dan melaporkan pekerjaan adalah tugas robot ---
            requires(GET, "/api/jobs", "jobs.read"),
            requires(GET, "/api/jobs/next", "jobs.update"),
            requires(GET, "/api/jobs/{id}", "jobs.read"),
            requires(POST, "/api/jobs", "jobs.create"),
            requires(POST, "/api/jobs/{id}/state", "jobs.update"),
            requires(POST, "/api/jobs/{id}/stop", "jobs.update"),
            requires(DELETE, "/api/jobs/{id}", "jobs.delete"),
            requires(GET, "/api/jobs/{id}/attachments", "jobs.read"),
            requires(GET, "/api/jobs/{id}/attachments/{attachmentId}/content", "jobs.read"),

            // --- pemicu ---
            requires(GET, "/api/triggers", "triggers.read"),
            requires(POST, "/api/triggers", "triggers.create", "triggers.update"),
            requires(POST, "/api/triggers/{name}/toggle", "triggers.update"),
            requires(DELETE, "/api/triggers/{name}", "triggers.delete"),

            // --- antrean: menambah, mengambil, dan melaporkan butir mengubah isi antreannya ---
            requires(GET, "/api/queues", "queues.read"),
            requires(POST, "/api/queues", "queues.create"),
            requires(PUT, "/api/queues/{name}/folder", "queues.update"),
            requires(DELETE, "/api/queues/{name}", "queues.delete"),
            requires(GET, "/api/queues/{name}/items", "queues.read"),
            requires(POST, "/api/queues/{name}/items", "queues.update"),
            requires(POST, "/api/queues/{name}/next", "queues.update"),
            requires(POST, "/api/queues/items/{id}/result", "queues.update"),
            requires(DELETE, "/api/queues/items/{id}", "queues.delete"),

            // --- aset, termasuk kredensial ---
            requires(GET, "/api/assets", "assets.read"),
            requires(POST, "/api/assets", "assets.create", "assets.update"),
            requires(PUT, "/api/assets/{name}/folder", "assets.update"),
            requires(GET, "/api/assets/{name}/value", "assets.read"),
            requires(DELETE, "/api/assets/{name}", "assets.delete"),
            requires(GET, "/api/credentials", "assets.read"),
            requires(POST, "/api/credentials", "assets.create", "assets.update"),
            requires(GET, "/api/credentials/{name}/value", "assets.read"),
            requires(DELETE, "/api/credentials/{name}", "assets.delete"),

            // --- ember penyimpanan ---
            requires(GET, "/api/buckets", "buckets.read"),
            requires(POST, "/api/buckets", "buckets.create"),
            requires(PUT, "/api/buckets/{name}/folder", "buckets.update"),
            requires(DELETE, "/api/buckets/{name}", "buckets.delete"),
            requires(GET, "/api/buckets/{name}/files", "buckets.read"),
            requires(POST, "/api/buckets/{name}/files", "buckets.update"),
            requires(GET, "/api/buckets/{name}/files/{id}/content", "buckets.read"),
            requires(DELETE, "/api/buckets/{name}/files/{id}", "buckets.delete"),

            // --- robot, mesin, lingkungan ---
            //
            // robots.update adalah izin DENYUT robot v1 — peran Robot memegangnya.
            // Karena itu mengubah SETELAN robot (termasuk akun Windows-nya) memakai
            // robots.create: akun robot tidak boleh mengubah sandi Windows robot lain.
            requires(GET, "/api/robots", "robots.read"),
            requires(GET, "/api/robots/{name}", "robots.read"),
            requires(POST, "/api/robots/{name}/heartbeat", "robots.update"),
            requires(POST, "/api/robots", "robots.create"),
            requires(PUT, "/api/robots/{name}", "robots.create"),
            requires(DELETE, "/api/robots/{name}", "robots.delete"),
            requires(GET, "/api/machines", "machines.read"),
            requires(POST, "/api/machines", "machines.create"),
            requires(PUT, "/api/machines/{name}", "machines.update"),
            requires(POST, "/api/machines/{name}/key", "machines.update"),
            requires(DELETE, "/api/machines/{name}/key", "machines.update"),
            requires(DELETE, "/api/machines/{name}", "machines.delete"),
            requires(GET, "/api/environments", "environments.read"),
            requires(POST, "/api/environments", "environments.create"),
            requires(DELETE, "/api/environments/{name}", "environments.delete"),

            // --- Robot Agent unattended (kontrak v2, ROBOT-API.md) ---
            open(POST, "/api/agent/login"),
            agent(POST, "/api/agent/heartbeat"),
            agent(POST, "/api/agent/jobs/claim"),
            agent(POST, "/api/agent/jobs/{id}/state"),
            agent(POST, "/api/agent/jobs/{id}/windows-credential"),
            agent(POST, "/api/agent/jobs/{id}/attachments"),
            agent(POST, "/api/agent/logs"),

            // --- catatan dan peringatan ---
            requires(GET, "/api/logs", "logs.read"),
            requires(POST, "/api/logs", "logs.create"),
            requires(DELETE, "/api/logs", "logs.delete"),
            requires(GET, "/api/alerts", "alerts.read"),
            requires(GET, "/api/alerts/summary", "alerts.read"),
            requires(POST, "/api/alerts/{id}/read", "alerts.update"),
            requires(POST, "/api/alerts/read-all", "alerts.update"),

            // --- pengguna dan peran: daftar peran juga dibaca layar Pengguna ---
            requires(GET, "/api/users", "users.read"),
            requires(POST, "/api/users", "users.create", "users.update"),
            requires(DELETE, "/api/users/{username}", "users.delete"),
            requires(GET, "/api/roles", "roles.read", "users.read"),
            requires(GET, "/api/permissions", "roles.read"),
            requires(POST, "/api/roles", "roles.create"),
            requires(PUT, "/api/roles/{name}", "roles.update"),
            requires(DELETE, "/api/roles/{name}", "roles.delete"),

            // --- penyewa, lisensi, setelan, audit ---
            requires(GET, "/api/tenants", "settings.read"),
            requires(GET, "/api/licensing", "settings.read"),
            requires(GET, "/api/settings", "settings.read"),
            requires(GET, "/api/audit", "audit.read"),
            requires(GET, "/api/audit/components", "audit.read"));

    private final PermissionChecker permissionChecker;
    private final AgentAccessService agentAccessService;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        // Hanya endpoint controller. Yang lain — berkas statis, halaman galat
        // bawaan — tidak punya aturan dan tidak perlu.
        if (!(handler instanceof HandlerMethod)) return true;

        Object pathPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        PermissionRule rule = pathPattern == null
                ? null
                : findRule(HttpMethod.valueOf(request.getMethod()), pathPattern.toString());

        if (rule == null) {
            log.error("Endpoint {} {} tidak punya aturan izin; ditolak.", request.getMethod(), pathPattern);
            throw ApiException.forbidden("Endpoint ini belum punya aturan izin.");
        }

        if (rule.access() == Access.PUBLIC) return true;

        OpenOrchestratorPrincipal principal = CurrentPrincipal.find()
                .orElseThrow(() -> ApiException.unauthorized(JwtAuthenticationFilter.INVALID_TOKEN_MESSAGE));

        if (principal.isAgent()) {
            agentAccessService.requireCurrentKey(principal);

            if (rule.access() == Access.AGENT) return true;

            if (PACKAGE_CONTENT_PATH.equals(rule.pathPattern())) {
                Map<String, String> variables = pathVariables(request);

                if (agentAccessService.canDownloadPackage(principal, variables.get("name"), variables.get("version"))) {
                    return true;
                }

                throw ApiException.forbidden("Paket ini tidak sedang dijalankan robot di mesin ini.");
            }

            throw ApiException.forbidden("Token Robot Agent hanya berlaku di /api/agent.");
        }

        if (rule.access() == Access.AGENT) {
            throw ApiException.forbidden("Endpoint ini khusus Robot Agent (masuk dengan machine key).");
        }

        if (principal.isExecutor()) {
            agentAccessService.requireActiveExecutor(principal);

            // Endpoint untuk orang (profil, beranda, folder): executor tidak punya urusan di sana.
            if (rule.access() == Access.AUTHENTICATED) {
                throw ApiException.forbidden("Token executor tidak berlaku untuk endpoint ini.");
            }
        }

        if (rule.access() == Access.AUTHENTICATED) return true;

        for (String permission : rule.anyOfPermissions()) {
            if (permissionChecker.isAllowed(principal, permission)) return true;
        }

        throw PermissionChecker.denied(rule.anyOfPermissions().getFirst());
    }

    static PermissionRule findRule(HttpMethod method, String pathPattern) {
        for (PermissionRule rule : RULES) {
            if (rule.method().equals(method) && rule.pathPattern().equals(pathPattern)) return rule;
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }
}
