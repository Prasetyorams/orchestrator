package id.jakforge.openorchestrator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.jakforge.openorchestrator.dto.response.ApiError;
import id.jakforge.openorchestrator.security.JwtAuthenticationFilter;
import id.jakforge.openorchestrator.security.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** Satu-satunya jalur yang boleh dicapai tanpa token. Sama dengan PermissionInterceptor.PUBLIC. */
    private static final String[] PUBLIC_PATHS =
            { "/", "/actuator/health", "/api/health", "/api/auth/login", "/api/agent/login",
              "/api/auth/assistant/token", "/api/auth/assistant/refresh", "/api/auth/assistant/logout" };

    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final List<String> ALLOWED_HEADERS = List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE);

    private final JwtService jwtService;
    private final OpenOrchestratorProperties properties;
    private final ObjectMapper objectMapper;

    // Tidak ada bean PasswordEncoder di sini.
    //
    // Sebelumnya ada BCrypt kekuatan 12, dan itu dibuang bukan karena BCrypt
    // buruk melainkan karena TIDAK DIPAKAI: kata sandi disimpan sebagai
    // PBKDF2-SHA256 oleh id.jakforge.openorchestrator.security.Passwords, mengikuti
    // hash yang sudah ada di basis data. Membiarkan encoder BCrypt tersedia
    // untuk disuntikkan berarti cepat atau lambat ada kode yang memakainya,
    // lalu menulis hash yang tidak akan pernah cocok dengan yang lain.

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF dimatikan karena API ini tanpa sesi dan tanpa cookie:
                // tokennya dikirim di header Authorization, dan header tidak
                // ikut terkirim sendiri oleh peramban seperti cookie.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
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
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
                    objectMapper.writeValue(response.getWriter(),
                            new ApiError(JwtAuthenticationFilter.INVALID_TOKEN_MESSAGE));
                }))

                // Dibuat di sini, bukan sebagai @Component: filter yang menjadi
                // bean ikut didaftarkan Spring Boot ke rantai filter servlet,
                // sehingga berada di dua rantai sekaligus.
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOrigins(properties.cors().allowedOriginList());
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        configuration.setMaxAge(properties.cors().maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
