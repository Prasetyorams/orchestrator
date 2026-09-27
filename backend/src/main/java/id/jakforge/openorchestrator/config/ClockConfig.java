package id.jakforge.openorchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Jam aplikasi, sebagai bean.
 *
 * <p>Bean, bukan {@code now()} yang dipanggil langsung: layanan yang bergantung
 * pada "sekarang" — batas "hari ini" di dasbor, waktu jalan berikutnya sebuah
 * pemicu — bisa diuji dengan jam yang dihentikan di saat tertentu.
 *
 * <p>Zonanya zona TAMPILAN ({@code openorchestrator.display-timezone}), jadi
 * {@code LocalDate.now(clock)} adalah "hari ini" menurut orang yang membuka
 * dasbor. Saat (instant) yang ditunjuknya tetap sama di zona mana pun.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(OpenOrchestratorProperties properties) {
        return Clock.system(properties.displayZone());
    }
}
