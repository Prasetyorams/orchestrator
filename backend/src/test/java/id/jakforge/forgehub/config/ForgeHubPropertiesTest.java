package id.jakforge.forgehub.config;

import id.jakforge.forgehub.support.TestProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * application.yml terbaca utuh ke {@link ForgeHubProperties}.
 *
 * <p>Tanpa uji ini, setelan yang salah nama baru ketahuan saat aplikasi naik —
 * atau tidak pernah, kalau medannya boleh kosong.
 */
class ForgeHubPropertiesTest {

    private static ForgeHubProperties bindApplicationYaml() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));

        for (PropertySource<?> source : sources) environment.getPropertySources().addLast(source);

        return Binder.get(environment).bind("forgehub", ForgeHubProperties.class).get();
    }

    @Test
    @DisplayName("nilai bawaan application.yml terbaca dengan tipe yang benar")
    void bindsApplicationYamlDefaults() throws Exception {
        ForgeHubProperties properties = bindApplicationYaml();

        assertEquals(Duration.ofSeconds(45), properties.robot().heartbeatTimeout());
        assertEquals(Duration.ofSeconds(10), properties.permissionCache().ttl());
        assertEquals(Duration.ofSeconds(15), properties.scheduler().initialDelay());
        assertEquals(Duration.ofSeconds(30), properties.scheduler().interval());
        assertEquals(DataSize.ofMegabytes(64), properties.limits().maxPackageSize());
        assertEquals(DataSize.ofMegabytes(32), properties.limits().maxBucketFileSize());
        assertEquals(Duration.ofHours(1), properties.cors().maxAge());
        assertEquals("FH_Admin", properties.bootstrap().adminUsername());
        assertEquals("default", properties.bootstrap().tenantName());
    }

    @Test
    @DisplayName("setelan bawaan lolos validasi; yang wajib tapi kosong ditolak")
    void validatesRequiredSettings() throws Exception {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertTrue(validator.validate(bindApplicationYaml()).isEmpty());

            Set<ConstraintViolation<ForgeHubProperties>> violations =
                    validator.validate(TestProperties.withDisplayTimezone(" "));
            assertFalse(violations.isEmpty());
            assertEquals("displayTimezone", violations.iterator().next().getPropertyPath().toString());
        }
    }

    @Test
    @DisplayName("alamat dasbor adalah asal CORS pertama, dengan cadangan localhost:3000")
    void dashboardUrlIsFirstCorsOrigin() {
        assertEquals("http://a.test", new ForgeHubProperties.Cors(" http://a.test , http://b.test", Duration.ZERO)
                .dashboardUrl());
        assertEquals(List.of("http://a.test", "http://b.test"),
                new ForgeHubProperties.Cors("http://a.test,, http://b.test ", Duration.ZERO).allowedOriginList());
        assertEquals("http://localhost:3000", new ForgeHubProperties.Cors("", Duration.ZERO).dashboardUrl());
    }

    @Test
    @DisplayName("zona tampilan yang tidak dikenal jatuh ke UTC, sama seperti zona pemicu")
    void unknownDisplayZoneFallsBackToUtc() {
        assertEquals("Z", TestProperties.withDisplayTimezone("Asia/Djakarta").displayZone().getId());
        assertEquals("Asia/Jakarta", TestProperties.withDisplayTimezone("Asia/Jakarta").displayZone().getId());
    }
}
