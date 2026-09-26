package id.jakforge.forgehub.config;

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
public class WebConfig implements WebMvcConfigurer {

    private final IzinInterceptor izin;
    private final AuditInterceptor audit;

    public WebConfig(IzinInterceptor izin, AuditInterceptor audit) {
        this.izin = izin;
        this.audit = audit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(izin).addPathPatterns("/api/**").order(0);
        registry.addInterceptor(audit).addPathPatterns("/api/**").order(1);
    }
}
