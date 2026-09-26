package id.jakforge.forgehub.config;

import id.jakforge.forgehub.security.Izin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tabel izin di {@link IzinInterceptor} dibandingkan dengan endpoint yang
 * BENAR-BENAR ada, dibaca langsung dari anotasi setiap controller.
 *
 * <p>Endpoint tanpa baris ditolak pencegatnya saat berjalan — aman, tapi
 * baru ketahuan saat seseorang memanggilnya. Uji ini membuatnya ketahuan
 * saat dibangun.
 */
class IzinInterceptorTest {

    /** "GET /api/jobs/{id}" untuk setiap metode di setiap @RestController. */
    private static Set<String> endpoint() throws Exception {
        var pemindai = new ClassPathScanningCandidateComponentProvider(false);
        pemindai.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> hasil = new LinkedHashSet<>();

        for (BeanDefinition def : pemindai.findCandidateComponents("id.jakforge.forgehub.controller")) {
            Class<?> kelas = Class.forName(def.getBeanClassName());
            RequestMapping akar = AnnotatedElementUtils.findMergedAnnotation(kelas, RequestMapping.class);
            String[] awalan = akar == null || akar.path().length == 0 ? new String[] { "" } : akar.path();

            for (Method m : kelas.getDeclaredMethods()) {
                RequestMapping peta = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (peta == null) continue;

                String[] jalur = peta.path().length == 0 ? new String[] { "" } : peta.path();

                for (RequestMethod metode : peta.method()) {
                    for (String a : awalan) {
                        for (String j : jalur) {
                            hasil.add(metode.name() + " " + gabung(a, j));
                        }
                    }
                }
            }
        }

        return hasil;
    }

    private static String gabung(String awalan, String jalur) {
        if (jalur.isEmpty()) return awalan;
        return awalan + (jalur.startsWith("/") ? jalur : "/" + jalur);
    }

    @Test
    @DisplayName("setiap endpoint /api punya aturan izin")
    void setiapEndpointBerizin() throws Exception {
        List<String> tanpa = new ArrayList<>();

        for (String e : endpoint()) {
            if (!e.split(" ")[1].startsWith("/api")) continue;

            String[] bagian = e.split(" ");
            if (IzinInterceptor.aturanUntuk(bagian[0], bagian[1]) == null) tanpa.add(e);
        }

        assertTrue(tanpa.isEmpty(), "Endpoint tanpa aturan izin di IzinInterceptor.ATURAN: " + tanpa);
    }

    @Test
    @DisplayName("tidak ada aturan untuk endpoint yang sudah tidak ada")
    void tidakAdaAturanBasi() throws Exception {
        Set<String> ada = endpoint();

        for (IzinInterceptor.Aturan a : IzinInterceptor.ATURAN) {
            assertTrue(ada.contains(a.metode() + " " + a.pola()), "Aturan basi: " + a);
        }
    }

    @Test
    @DisplayName("setiap izin di tabel dikenal katalog — salah ketik berarti endpoint tertutup untuk semua kecuali Administrator")
    void izinDikenal() {
        for (IzinInterceptor.Aturan a : IzinInterceptor.ATURAN) {
            if (a.izin().equals(IzinInterceptor.PUBLIK) || a.izin().equals(IzinInterceptor.MASUK)) continue;

            for (String x : a.izin().split("\\|")) {
                assertTrue(Izin.jabarkan(List.of(x)).contains(x), "Izin tidak dikenal di " + a + ": " + x);
            }
        }
    }

    @Test
    @DisplayName("hanya kesehatan dan masuk yang terbuka tanpa token — sama dengan SecurityConfig")
    void publik() {
        List<String> publik = IzinInterceptor.ATURAN.stream()
                .filter(a -> a.izin().equals(IzinInterceptor.PUBLIK))
                .map(a -> a.metode() + " " + a.pola())
                .toList();

        assertEquals(List.of("GET /api/health", "POST /api/auth/login"), publik);
    }

    /** Yang dipanggil JakRunner dan activity Studio selama automasi berjalan. */
    private static final List<String> ROBOT = List.of(
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
    private static final List<List<String>> PERAN_ROBOT = List.of(
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
    void robotTetapBekerja() {
        for (List<String> peran : PERAN_ROBOT) {
            for (String e : ROBOT) {
                String[] bagian = e.split(" ");
                IzinInterceptor.Aturan a = IzinInterceptor.aturanUntuk(bagian[0], bagian[1]);

                assertNotNull(a, e);

                boolean boleh = false;
                for (String x : a.izin().split("\\|")) boleh |= Izin.cocok(peran, x);

                if (!boleh) fail("Peran " + peran + " tidak bisa memanggil " + e + " (" + a.izin() + ")");
            }
        }
    }
}
