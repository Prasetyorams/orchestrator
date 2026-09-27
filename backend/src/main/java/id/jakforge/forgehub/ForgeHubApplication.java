package id.jakforge.forgehub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * ForgeHub: orchestrator JakForge.
 *
 * Mengelola robot, proses, pekerjaan, antrean, aset, dan catatan jalannya
 * automasi — dan menerima kiriman log dari JakRunner maupun Studio.
 *
 * <p>UserDetailsServiceAutoConfiguration dimatikan: pemanggil dikenali dari
 * token JWT, bukan dari daftar pengguna Spring Security. Tanpa ini Spring Boot
 * membuat pengguna bawaan "user" yang tidak pernah dipakai dan mencetak kata
 * sandi acaknya ke catatan server setiap kali aplikasi naik.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class ForgeHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForgeHubApplication.class, args);
    }
}
