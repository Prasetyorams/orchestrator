package id.jakforge.forgehub.config;

import id.jakforge.forgehub.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter,
                          @Value("${forgehub.cors.allowed-origins}") String allowedOrigins) {
        this.jwtFilter = jwtFilter;
        this.allowedOrigins = allowedOrigins;
    }

    // Tidak ada bean PasswordEncoder di sini.
    //
    // Sebelumnya ada BCrypt kekuatan 12, dan itu dibuang bukan karena BCrypt
    // buruk melainkan karena TIDAK DIPAKAI: kata sandi disimpan sebagai
    // PBKDF2-SHA256 oleh id.jakforge.forgehub.security.Passwords, mengikuti
    // hash yang sudah ada di basis data. Membiarkan encoder BCrypt tersedia
    // untuk disuntikkan berarti cepat atau lambat ada kode yang memakainya,
    // lalu menulis hash yang tidak akan pernah cocok dengan yang lain.

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CSRF dimatikan karena API ini tanpa sesi dan tanpa cookie:
                // tokennya dikirim di header Authorization, dan header tidak
                // ikut terkirim sendiri oleh peramban seperti cookie.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/", "/actuator/health", "/api/health").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())

                // 401 untuk permintaan TANPA identitas yang sah, bukan 403.
                //
                // Bawaan Spring Security di sini adalah 403, dan itu tampak
                // seperti perbedaan kosmetik sampai seseorang melihat apa yang
                // dilakukan kliennya: Studio, JakRunner, dan activity
                // Orchestrator semuanya MEMBUANG TOKENNYA saat menerima 401,
                // lalu masuk lagi. Dengan 403 mereka menyimpan token yang sudah
                // kedaluwarsa selamanya — jadi robot berhenti bekerja delapan
                // jam setelah dinyalakan, dan gejalanya bukan "token
                // kedaluwarsa" melainkan setiap panggilan gagal tanpa sebab
                // yang jelas.
                //
                // 403 tetap dipakai untuk yang sudah dikenali tapi tidak
                // berhak; itu memang bukan urusan token.
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"error\":\"Token tidak sah atau sudah kedaluwarsa.\"}");
                }))

                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
