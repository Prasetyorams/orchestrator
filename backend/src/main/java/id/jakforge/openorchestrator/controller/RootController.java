package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Locale;
import java.util.Set;

/**
 * Halaman akar: mengarahkan ke dasbor.
 *
 * <p>Ada karena OpenOrchestrator .NET menyajikan API DAN dasbor dari satu alamat yang
 * sama, sedangkan di sini keduanya terpisah — Next.js di 3000, API di 8080.
 * Orang yang terbiasa membuka 8080 akan tetap membukanya, dan yang diterimanya
 * adalah {@code {"error":"Token tidak sah atau sudah kedaluwarsa."}}: jawaban
 * yang benar untuk permintaan API tanpa token, tapi jalan buntu tanpa petunjuk
 * bagi orang yang sebenarnya mencari dasbor.
 *
 * <p>Yang dikembalikan adalah pengalihan, bukan halaman penjelasan. Penjelasan
 * menuntut orangnya membaca lalu mengetik ulang alamat; pengalihan langsung
 * membawanya ke tempat yang dicari.
 */
@RestController
@RequiredArgsConstructor
public class RootController {

    /** Halaman persetujuan Open Assistant di dasbor (V11). */
    static final String ASSISTANT_CONNECT_PATH = "/assistant/connect";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final OpenOrchestratorProperties properties;

    /** Alamat dasbor: asal pertama di CORS_ORIGINS (lihat {@link OpenOrchestratorProperties.Cors#dashboardUrl()}). */
    @GetMapping("/")
    public ResponseEntity<Void> redirectToDashboard(HttpServletRequest request) {
        return redirect(dashboardUrlFor(properties.cors().dashboardUrl(), request.getServerName()));
    }

    /**
     * Masuk lewat dasbor dari Open Assistant: halaman persetujuannya ada di
     * dasbor, tapi Open Assistant hanya tahu alamat API — yang sama dipakainya
     * untuk {@code /api/health} dan {@code /api/auth/assistant/token} — jadi
     * peramban membuka {@code <API>/assistant/connect}. Pengalihan ini
     * membawanya ke halaman dasbor dengan parameter yang sama persis; dasbor
     * yang memeriksanya.
     */
    @GetMapping(ASSISTANT_CONNECT_PATH)
    public ResponseEntity<Void> redirectAssistantConnect(HttpServletRequest request) {
        String query = request.getQueryString();
        String dashboard = dashboardUrlFor(properties.cors().dashboardUrl(), request.getServerName());

        return redirect(dashboard + ASSISTANT_CONNECT_PATH + (query == null || query.isEmpty() ? "" : "?" + query));
    }

    /**
     * Alamat dasbor untuk peramban yang bertanya. "localhost" di CORS_ORIGINS
     * berarti "komputer server ini": peramban di VM atau PC lain yang membuka
     * API lewat alamat IP diarahkan ke dasbor di alamat yang sama — bukan ke
     * localhost-nya sendiri, yang tidak punya dasbor. Sama seperti dasbor
     * mengabaikan NEXT_PUBLIC_API_URL yang localhost.
     *
     * @param requestHost nama host yang dipakai peramban, tanpa port
     * @return tanpa garis miring di ujung
     */
    static String dashboardUrlFor(String configured, String requestHost) {
        String base = configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;

        try {
            UriComponents uri = UriComponentsBuilder.fromUriString(base).build();

            if (uri.getHost() != null && isLoopback(uri.getHost())
                    && requestHost != null && !requestHost.isBlank() && !isLoopback(requestHost)) {
                return UriComponentsBuilder.fromUriString(base).host(requestHost).build().toUriString();
            }
        } catch (IllegalArgumentException e) {
            // Alamat di CORS_ORIGINS yang tidak bisa diurai dipakai apa adanya.
        }

        return base;
    }

    private static boolean isLoopback(String host) {
        return LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
