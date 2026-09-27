package id.jakforge.forgehub.support;

import id.jakforge.forgehub.config.ForgeHubProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/** Setelan untuk uji, dengan nilai yang sama seperti bawaan di application.yml. */
public final class TestProperties {

    private TestProperties() {
    }

    public static ForgeHubProperties defaults() {
        return withDisplayTimezone("UTC");
    }

    public static ForgeHubProperties withDisplayTimezone(String displayTimezone) {
        return new ForgeHubProperties(
                displayTimezone,
                new ForgeHubProperties.Secret("/data/signing.key"),
                new ForgeHubProperties.Jwt("k".repeat(32), 480),
                new ForgeHubProperties.Cors("http://localhost:3000", Duration.ofHours(1)),
                new ForgeHubProperties.Robot(Duration.ofSeconds(45)),
                new ForgeHubProperties.PermissionCache(Duration.ofSeconds(10)),
                new ForgeHubProperties.Scheduler(Duration.ofSeconds(15), Duration.ofSeconds(30)),
                new ForgeHubProperties.Limits(DataSize.ofMegabytes(64), DataSize.ofMegabytes(32)),
                new ForgeHubProperties.Bootstrap("default", "FH_Admin", "forgehub", "ForgeHub Administrator", ""));
    }
}
