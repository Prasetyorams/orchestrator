package id.jakforge.openorchestrator.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Menyalakan {@code @Scheduled}.
 *
 * <p>Di kelas konfigurasinya sendiri, bukan menempel di sebuah service:
 * penjadwalan adalah keputusan aplikasi, dan service yang kebetulan
 * membawanya membuat penjadwal ikut mati begitu service itu dipindah.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
