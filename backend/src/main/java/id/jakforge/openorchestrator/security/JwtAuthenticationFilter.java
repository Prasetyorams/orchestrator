package id.jakforge.openorchestrator.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Mengenali pemanggil dari header {@code Authorization: Bearer ...}.
 *
 * <p>Bukan {@code @Component}: dipasang {@code SecurityConfig} langsung ke rantai
 * Spring Security. Filter yang menjadi bean ikut didaftarkan Spring Boot ke
 * rantai filter servlet juga, dan berjalan di dua tempat.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Jawaban untuk permintaan tanpa token yang sah — dibaca klien sebagai "masuk lagi". */
    public static final String INVALID_TOKEN_MESSAGE = "Token tidak sah atau sudah kedaluwarsa.";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());

            try {
                OpenOrchestratorPrincipal principal = jwtService.parsePrincipal(token);

                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority(ROLE_AUTHORITY_PREFIX + principal.role())));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ex) {
                // Token rusak, palsu, kedaluwarsa, atau isinya tidak lengkap:
                // dibiarkan tanpa autentikasi, lalu SecurityConfig yang
                // menolaknya dengan 401. Melempar dari sini hanya menghasilkan
                // 500 untuk hal yang sepenuhnya normal.
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response);
    }
}
