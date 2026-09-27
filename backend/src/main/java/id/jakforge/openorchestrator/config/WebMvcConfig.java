package id.jakforge.openorchestrator.config;

import id.jakforge.openorchestrator.audit.AuditInterceptor;
import id.jakforge.openorchestrator.security.PermissionInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Pemasangan pencegat Spring MVC.
 *
 * <p>Izin LEBIH DULU: permintaan yang ditolak tidak pernah sampai ke pencatat
 * audit, jadi jejak audit hanya berisi perubahan yang memang terjadi.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private static final String API_PATHS = "/api/**";

    private final PermissionInterceptor permissionInterceptor;
    private final AuditInterceptor auditInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor).addPathPatterns(API_PATHS).order(0);
        registry.addInterceptor(auditInterceptor).addPathPatterns(API_PATHS).order(1);
    }
}
