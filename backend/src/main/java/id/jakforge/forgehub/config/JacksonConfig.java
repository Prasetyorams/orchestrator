package id.jakforge.forgehub.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    /**
     * Waktu dikirim sebagai teks ISO-8601, bukan angka detik.
     *
     * Sisi frontend membacanya dengan new Date(...) dan menampilkannya apa
     * adanya; angka epoch di sana tampil sebagai 1970 setiap kali seseorang
     * lupa mengalikan seribu.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer isoDates() {
        return builder -> builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
