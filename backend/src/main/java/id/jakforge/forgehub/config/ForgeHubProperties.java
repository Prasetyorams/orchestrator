package id.jakforge.forgehub.config;

import id.jakforge.forgehub.common.Cron;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/**
 * Seluruh setelan ForgeHub ({@code forgehub.*} di application.yml), dibaca SEKALI
 * menjadi record bertipe.
 *
 * <p>Satu tempat, bukan {@code @Value} yang tersebar di tujuh kelas: nama setelan
 * yang salah ketik di salah satunya baru ketahuan saat kelas itu dibuat — atau
 * tidak pernah, kalau ada nilai bawaan di anotasinya. Di sini setelan yang
 * hilang atau tidak sah menghentikan aplikasi saat NAIK ({@code @Validated}),
 * dengan pesan yang menyebut nama setelannya.
 *
 * <p>Nilai bawaannya ada di application.yml, tidak diulang di sini.
 */
@Validated
@ConfigurationProperties(prefix = "forgehub")
public record ForgeHubProperties(
        @NotBlank String displayTimezone,
        @Valid @NotNull Secret secret,
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Robot robot,
        @Valid @NotNull PermissionCache permissionCache,
        @Valid @NotNull Scheduler scheduler,
        @Valid @NotNull Limits limits,
        @Valid @NotNull Bootstrap bootstrap) {

    /**
     * Zona tampilan untuk batas "hari ini" di dasbor; UTC kalau namanya tidak
     * dikenal, sama seperti zona pemicu.
     */
    public ZoneId displayZone() {
        return Cron.zoneOrUtc(displayTimezone);
    }

    /** @param keyFile berkas signing.key untuk menyandikan kredensial */
    public record Secret(@NotBlank String keyFile) {
    }

    /** @param expirationMinutes umur token, dalam menit (JWT_EXPIRATION_MINUTES) */
    public record Jwt(@NotBlank String secret, @Positive long expirationMinutes) {

        public Duration expiration() {
            return Duration.ofMinutes(expirationMinutes);
        }
    }

    /** @param allowedOrigins asal yang boleh memanggil API, dipisah koma (CORS_ORIGINS) */
    public record Cors(@NotNull String allowedOrigins, @NotNull Duration maxAge) {

        /** Alamat dasbor kalau CORS_ORIGINS kosong. */
        static final String DEFAULT_DASHBOARD_URL = "http://localhost:3000";

        public List<String> allowedOriginList() {
            return Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .filter(origin -> !origin.isEmpty())
                    .toList();
        }

        /**
         * Alamat dasbor: asal PERTAMA di CORS_ORIGINS.
         *
         * <p>Keduanya menjawab pertanyaan yang sama — "di mana dasbornya" — dan
         * dua tempat yang menjawabnya sendiri-sendiri akan berbeda begitu salah
         * satunya diubah.
         */
        public String dashboardUrl() {
            String first = allowedOrigins.split(",")[0].trim();
            return first.isEmpty() ? DEFAULT_DASHBOARD_URL : first;
        }
    }

    /** @param heartbeatTimeout setelah berapa lama tanpa denyut robot dianggap terputus */
    public record Robot(@NotNull Duration heartbeatTimeout) {
    }

    /** @param ttl berapa lama izin peran seseorang diingat */
    public record PermissionCache(@NotNull Duration ttl) {
    }

    public record Scheduler(@NotNull Duration initialDelay, @NotNull Duration interval) {
    }

    public record Limits(@NotNull DataSize maxPackageSize, @NotNull DataSize maxBucketFileSize) {
    }

    /**
     * Isi awal yang ditanam saat aplikasi naik.
     *
     * @param machineName nama mesin ForgeHub; kosong berarti nama host
     */
    public record Bootstrap(
            @NotBlank String tenantName,
            @NotBlank String adminUsername,
            @NotBlank String adminPassword,
            @NotBlank String adminDisplayName,
            String machineName) {
    }
}
