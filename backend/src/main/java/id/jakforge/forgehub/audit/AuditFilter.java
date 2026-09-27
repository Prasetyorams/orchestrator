package id.jakforge.forgehub.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.util.Set;

/**
 * Menyimpan AWAL badan permintaan yang mengubah sesuatu, supaya jejak audit
 * bisa menyebut NAMA yang diubah ("Proses · Simpan · Tagihan Harian"), bukan
 * sekadar jalurnya.
 *
 * <p>Badan permintaan hanya bisa dibaca sekali, dan yang membacanya adalah
 * controller. Pembungkus ini menyalin apa yang dibaca controller, paling
 * banyak {@link #MAX_CACHED_BODY_BYTES} bita: nama selalu ada di depan,
 * sedangkan paket dan berkas bisa puluhan megabita dan tidak ada gunanya
 * disalin.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuditFilter extends OncePerRequestFilter {

    static final int MAX_CACHED_BODY_BYTES = 16 * 1024;

    private static final String API_PATH_PREFIX = "/api/";
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !MUTATING_METHODS.contains(request.getMethod()) || !request.getRequestURI().startsWith(API_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        chain.doFilter(new ContentCachingRequestWrapper(request, MAX_CACHED_BODY_BYTES), response);
    }
}
