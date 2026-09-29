package id.jakforge.openorchestrator.support;

import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/** Setelan untuk uji, dengan nilai yang sama seperti bawaan di application.yml. */
public final class TestProperties {

    private TestProperties() {
    }

    public static OpenOrchestratorProperties defaults() {
        return withDisplayTimezone("UTC");
    }

    public static OpenOrchestratorProperties withDisplayTimezone(String displayTimezone) {
        return new OpenOrchestratorProperties(
                displayTimezone,
                new OpenOrchestratorProperties.Secret("/data/signing.key"),
                new OpenOrchestratorProperties.Jwt("k".repeat(32), 480),
                new OpenOrchestratorProperties.Cors("http://localhost:3000", Duration.ofHours(1)),
                new OpenOrchestratorProperties.Robot(Duration.ofSeconds(45)),
                new OpenOrchestratorProperties.PermissionCache(Duration.ofSeconds(10)),
                new OpenOrchestratorProperties.Scheduler(Duration.ofSeconds(15), Duration.ofSeconds(30)),
                new OpenOrchestratorProperties.Limits(DataSize.ofMegabytes(64), DataSize.ofMegabytes(32)),
                new OpenOrchestratorProperties.Bootstrap("default", "OO_Admin", "openorchestrator", "OpenOrchestrator Administrator", ""),
                agentDefaults());
    }

    public static OpenOrchestratorProperties.Agent agentDefaults() {
        return new OpenOrchestratorProperties.Agent(Duration.ofHours(1), 15, 5, Duration.ofSeconds(60),
                Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofSeconds(10),
                "1.0.0", Duration.ofSeconds(60), DataSize.ofMegabytes(2), 5, DataSize.ofMegabytes(1));
    }
}
