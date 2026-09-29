package id.jakforge.openorchestrator.security;

import id.jakforge.openorchestrator.security.PermissionInterceptor.Access;
import id.jakforge.openorchestrator.security.PermissionInterceptor.PermissionRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tabel izin di {@link PermissionInterceptor} dibandingkan dengan endpoint yang
 * BENAR-BENAR ada, dibaca langsung dari anotasi setiap controller.
 *
 * <p>Endpoint tanpa baris ditolak pencegatnya saat berjalan — aman, tapi
 * baru ketahuan saat seseorang memanggilnya. Uji ini membuatnya ketahuan
 * saat dibangun.
 */
class PermissionInterceptorTest {

    private static final String CONTROLLER_PACKAGE = "id.jakforge.openorchestrator.controller";

    /** "GET /api/jobs/{id}" untuk setiap metode di setiap @RestController. */
    private static Set<String> declaredEndpoints() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> endpoints = new LinkedHashSet<>();

        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] prefixes = classMapping == null || classMapping.path().length == 0
                    ? new String[] { "" }
                    : classMapping.path();

            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (methodMapping == null) continue;

                String[] paths = methodMapping.path().length == 0 ? new String[] { "" } : methodMapping.path();

                for (RequestMethod httpMethod : methodMapping.method()) {
                    for (String prefix : prefixes) {
                        for (String path : paths) {
                            endpoints.add(httpMethod.name() + " " + join(prefix, path));
                        }
                    }
                }
            }
        }

        return endpoints;
    }

    private static String join(String prefix, String path) {
        if (path.isEmpty()) return prefix;
        return prefix + (path.startsWith("/") ? path : "/" + path);
    }

    private static PermissionRule ruleFor(String endpoint) {
        String[] parts = endpoint.split(" ");
        return PermissionInterceptor.findRule(HttpMethod.valueOf(parts[0]), parts[1]);
    }

    @Test
    @DisplayName("setiap endpoint /api punya aturan izin")
    void everyApiEndpointHasRule() throws Exception {
        List<String> endpointsWithoutRule = new ArrayList<>();

        for (String endpoint : declaredEndpoints()) {
            if (!endpoint.split(" ")[1].startsWith("/api")) continue;

            if (ruleFor(endpoint) == null) endpointsWithoutRule.add(endpoint);
        }

        assertTrue(endpointsWithoutRule.isEmpty(),
                "Endpoint tanpa aturan izin di PermissionInterceptor.RULES: " + endpointsWithoutRule);
    }

    @Test
    @DisplayName("tidak ada aturan untuk endpoint yang sudah tidak ada")
    void noStaleRules() throws Exception {
        Set<String> endpoints = declaredEndpoints();

        for (PermissionRule rule : PermissionInterceptor.RULES) {
            assertTrue(endpoints.contains(rule.method().name() + " " + rule.pathPattern()), "Aturan basi: " + rule);
        }
    }

    @Test
    @DisplayName("tidak ada aturan ganda untuk endpoint yang sama")
    void noDuplicateRules() {
        Set<String> seen = new LinkedHashSet<>();

        for (PermissionRule rule : PermissionInterceptor.RULES) {
            assertTrue(seen.add(rule.method().name() + " " + rule.pathPattern()), "Aturan ganda: " + rule);
        }
    }

    @Test
    @DisplayName("setiap izin di tabel dikenal katalog — salah ketik berarti endpoint tertutup untuk semua kecuali Administrator")
    void everyPermissionIsKnown() {
        for (PermissionRule rule : PermissionInterceptor.RULES) {
            if (rule.access() != Access.PERMISSION) {
                assertTrue(rule.anyOfPermissions().isEmpty(), "Aturan terbuka tidak butuh izin: " + rule);
                continue;
            }

            assertFalse(rule.anyOfPermissions().isEmpty(), "Aturan tanpa izin: " + rule);

            for (String permission : rule.anyOfPermissions()) {
                assertTrue(PermissionCatalog.expand(List.of(permission)).contains(permission),
                        "Izin tidak dikenal di " + rule + ": " + permission);
            }
        }
    }

    @Test
    @DisplayName("hanya kesehatan dan masuk yang terbuka tanpa token — sama dengan SecurityConfig")
    void onlyHealthAndLoginArePublic() {
        List<String> publicEndpoints = PermissionInterceptor.RULES.stream()
                .filter(rule -> rule.access() == Access.PUBLIC)
                .map(rule -> rule.method().name() + " " + rule.pathPattern())
                .toList();

        assertEquals(List.of("GET /api/health", "POST /api/auth/login", "POST /api/agent/login"), publicEndpoints);
    }

    /** Yang dipanggil JakRunner dan activity Studio selama automasi berjalan. */
    private static final List<String> ROBOT_ENDPOINTS = List.of(
            "POST /api/robots/{name}/heartbeat",
            "GET /api/jobs/next",
            "POST /api/jobs/{id}/state",
            "GET /api/jobs/{id}",
            "POST /api/jobs",
            "POST /api/logs",
            "GET /api/assets/{name}/value",
            "GET /api/credentials/{name}/value",
            "POST /api/queues/{name}/items",
            "POST /api/queues/{name}/next",
            "POST /api/queues/items/{id}/result");

    /** Isi peran bawaan sesudah V6 — lihat V6__peran_kustom.sql. */
    private static final List<List<String>> ROBOT_CAPABLE_ROLES = List.of(
            List.of("robots.update", "jobs.read", "jobs.create", "jobs.update", "logs.create", "assets.read",
                    "queues.read", "queues.update", "buckets.read", "buckets.update"),
            List.of("processes.*", "jobs.*", "triggers.*", "packages.*", "queues.*", "assets.*", "buckets.*",
                    "robots.read", "robots.update", "logs.read", "logs.create", "folders.read",
                    "machines.read", "environments.read", "alerts.read"),
            List.of("processes.read", "jobs.read", "jobs.create", "jobs.update", "triggers.read", "packages.read",
                    "queues.read", "queues.update", "assets.read", "buckets.read",
                    "robots.read", "robots.update", "logs.read", "logs.create", "alerts.read"));

    @Test
    @DisplayName("peran Robot, Automation Developer, dan Automation User cukup untuk menjalankan robot")
    void robotRolesKeepWorking() {
        for (List<String> rolePatterns : ROBOT_CAPABLE_ROLES) {
            for (String endpoint : ROBOT_ENDPOINTS) {
                PermissionRule rule = ruleFor(endpoint);

                assertNotNull(rule, endpoint);

                boolean allowed = rule.anyOfPermissions().stream()
                        .anyMatch(permission -> PermissionCatalog.matches(rolePatterns, permission));

                if (!allowed) fail("Peran " + rolePatterns + " tidak bisa memanggil " + endpoint + " (" + rule + ")");
            }
        }
    }

    /** Yang dipanggil activity kategori Orchestrator dari dalam workflow, lewat token executor. */
    private static final List<String> ACTIVITY_ENDPOINTS = List.of(
            "GET /api/assets/{name}/value",
            "GET /api/credentials/{name}/value",
            "POST /api/queues/{name}/items",
            "POST /api/queues/{name}/next",
            "POST /api/queues/items/{id}/result",
            "POST /api/jobs",
            "GET /api/jobs/{id}");

    @Test
    @DisplayName("token executor cukup untuk semua activity Orchestrator, dan tidak untuk mengambil job")
    void executorRunsActivities() {
        for (String endpoint : ACTIVITY_ENDPOINTS) {
            PermissionRule rule = ruleFor(endpoint);

            assertNotNull(rule, endpoint);

            boolean allowed = rule.anyOfPermissions().stream()
                    .anyMatch(permission -> PermissionCatalog.matches(PermissionService.EXECUTOR_PATTERNS, permission));

            if (!allowed) fail("Executor tidak bisa memanggil " + endpoint + " (" + rule + ")");
        }

        PermissionRule claim = ruleFor("GET /api/jobs/next");
        assertTrue(claim.anyOfPermissions().stream()
                .noneMatch(permission -> PermissionCatalog.matches(PermissionService.EXECUTOR_PATTERNS, permission)));
    }

    @Test
    @DisplayName("semua endpoint /api/agent kecuali login hanya untuk token agent")
    void agentEndpointsAreAgentOnly() {
        for (PermissionRule rule : PermissionInterceptor.RULES) {
            if (!rule.pathPattern().startsWith("/api/agent/")) continue;

            Access expected = rule.pathPattern().equals("/api/agent/login") ? Access.PUBLIC : Access.AGENT;
            assertEquals(expected, rule.access(), rule.method() + " " + rule.pathPattern());
        }
    }
}
