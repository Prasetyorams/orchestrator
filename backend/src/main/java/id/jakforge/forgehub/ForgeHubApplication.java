package id.jakforge.forgehub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ForgeHub: orchestrator JakForge.
 *
 * Mengelola robot, proses, pekerjaan, antrean, aset, dan catatan jalannya
 * automasi — dan menerima kiriman log dari JakRunner maupun Studio.
 */
@SpringBootApplication
public class ForgeHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForgeHubApplication.class, args);
    }
}
