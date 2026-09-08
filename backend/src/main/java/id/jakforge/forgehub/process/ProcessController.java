package id.jakforge.forgehub.process;

import id.jakforge.forgehub.common.NotFoundException;
import id.jakforge.forgehub.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/processes")
public class ProcessController {

    private final ProcessRepositories.Processes processes;
    private final ProcessRepositories.Packages packages;

    public ProcessController(ProcessRepositories.Processes processes,
                             ProcessRepositories.Packages packages) {
        this.processes = processes;
        this.packages = packages;
    }

    public record ProcessView(String id, String name, String description, String entryPoint,
                              String packageName, String packageVersion) {
    }

    public record CreateProcess(@NotBlank String name, String description,
                                String packageId, String environmentId, String entryPoint) {
    }

    public record PackageView(String id, String name, String version, String description) {
    }

    @GetMapping
    public List<ProcessView> list() {
        var tenantId = CurrentUser.get().tenantId();
        var allPackages = packages.findByTenantIdOrderByNameAscVersionDesc(tenantId);

        return processes.findByTenantIdOrderByNameAsc(tenantId).stream()
                .map(p -> {
                    var pack = allPackages.stream()
                            .filter(x -> x.getId().equals(p.getPackageId()))
                            .findFirst();

                    return new ProcessView(
                            p.getId().toString(),
                            p.getName(),
                            p.getDescription(),
                            p.getEntryPoint(),
                            pack.map(AutomationPackage::getName).orElse(null),
                            pack.map(AutomationPackage::getVersion).orElse(null));
                })
                .toList();
    }

    @PostMapping
    public ProcessView create(@RequestBody CreateProcess request) {
        var tenantId = CurrentUser.get().tenantId();

        var process = new AutomationProcess();
        process.setTenantId(tenantId);
        process.setName(request.name());
        process.setDescription(request.description());
        if (request.entryPoint() != null && !request.entryPoint().isBlank()) {
            process.setEntryPoint(request.entryPoint());
        }
        if (request.packageId() != null) process.setPackageId(UUID.fromString(request.packageId()));
        if (request.environmentId() != null) process.setEnvironmentId(UUID.fromString(request.environmentId()));

        processes.save(process);

        return list().stream()
                .filter(v -> v.id().equals(process.getId().toString()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Proses"));
    }

    @GetMapping("/packages")
    public List<PackageView> packages() {
        return packages.findByTenantIdOrderByNameAscVersionDesc(CurrentUser.get().tenantId()).stream()
                .map(p -> new PackageView(p.getId().toString(), p.getName(), p.getVersion(), p.getDescription()))
                .toList();
    }
}
