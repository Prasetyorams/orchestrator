package id.jakforge.forgehub.config;

import id.jakforge.forgehub.security.JwtService;
import id.jakforge.forgehub.security.SecretBox;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Kunci token dan kunci penyandian kredensial, dibuat dari setelan.
 *
 * <p>Lewat {@code @Bean}, bukan {@code @Component} di kelasnya: keduanya
 * menurunkan kunci di konstruktor dari nilai setelan, dan tetap berupa kelas
 * biasa yang bisa dibuat langsung di uji dengan kunci sementara.
 */
@Configuration
public class CryptoConfig {

    @Bean
    public JwtService jwtService(ForgeHubProperties properties) {
        return new JwtService(properties.jwt().secret(), properties.jwt().expirationMinutes());
    }

    @Bean
    public SecretBox secretBox(ForgeHubProperties properties) throws IOException {
        return new SecretBox(properties.secret().keyFile());
    }
}
