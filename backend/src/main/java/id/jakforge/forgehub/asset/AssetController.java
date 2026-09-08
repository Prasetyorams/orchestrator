package id.jakforge.forgehub.asset;

import id.jakforge.forgehub.common.NotFoundException;
import id.jakforge.forgehub.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetRepository assets;

    public AssetController(AssetRepository assets) {
        this.assets = assets;
    }

    public record AssetView(String id, String name, String assetType, String value, String description) {
    }

    public record UpsertAsset(@NotBlank String name, String assetType, String value, String description) {
    }

    @GetMapping
    public List<AssetView> list() {
        return assets.findByTenantIdOrderByNameAsc(CurrentUser.get().tenantId()).stream()
                .map(AssetController::view)
                .toList();
    }

    @PostMapping
    public AssetView upsert(@RequestBody UpsertAsset request) {
        var tenantId = CurrentUser.get().tenantId();

        var asset = assets.findByTenantIdAndNameIgnoreCase(tenantId, request.name())
                .orElseGet(() -> {
                    var created = new Asset();
                    created.setTenantId(tenantId);
                    created.setName(request.name());
                    return created;
                });

        if (request.assetType() != null) asset.setAssetType(request.assetType());
        asset.setValueText(request.value());
        asset.setDescription(request.description());

        return view(assets.save(asset));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        var tenantId = CurrentUser.get().tenantId();

        var asset = assets.findByIdAndTenantId(UUID.fromString(id), tenantId)
                .orElseThrow(() -> new NotFoundException("Aset"));

        assets.delete(asset);
    }

    /**
     * Aset bertipe CREDENTIAL tidak pernah mengirimkan nilainya lewat API
     * daftar. Yang membutuhkannya adalah robot, lewat jalur tersendiri —
     * bukan layar yang bisa dibuka siapa saja yang lewat.
     */
    private static AssetView view(Asset asset) {
        var value = "CREDENTIAL".equals(asset.getAssetType()) ? "********" : asset.getValueText();
        return new AssetView(asset.getId().toString(), asset.getName(), asset.getAssetType(),
                value, asset.getDescription());
    }
}
