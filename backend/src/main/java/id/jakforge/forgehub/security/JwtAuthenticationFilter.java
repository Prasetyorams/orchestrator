package id.jakforge.forgehub.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            try {
                Claims claims = jwtService.parse(token);

                ForgeHubPrincipal principal = new ForgeHubPrincipal(
                        UUID.fromString(claims.getSubject()),
                        UUID.fromString(claims.get("tenantId", String.class)),
                        claims.get("username", String.class),
                        claims.get("role", String.class));

                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ex) {
                // Token rusak atau kedaluwarsa: dibiarkan tanpa autentikasi,
                // lalu SecurityConfig yang menolaknya dengan 401. Melempar dari
                // sini hanya menghasilkan 500 untuk hal yang sepenuhnya normal.
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response);
    }
}
