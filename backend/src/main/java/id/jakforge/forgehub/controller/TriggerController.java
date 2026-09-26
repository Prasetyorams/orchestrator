package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.common.Badan;
import id.jakforge.forgehub.dto.TriggerRequest;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.security.Izin;
import id.jakforge.forgehub.service.FolderService;
import id.jakforge.forgehub.service.TriggerService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Pemicu terjadwal. */
@RestController
@RequestMapping("/api/triggers")
public class TriggerController {

    private final TriggerService service;
    private final FolderService folders;
    private final Izin izin;

    public TriggerController(TriggerService service, FolderService folders, Izin izin) {
        this.service = service;
        this.folders = folders;
        this.izin = izin;
    }

    /**
     * Tanpa {@code folderId}: pemicu seluruh penyewa.
     *
     * <p>Pemicu tinggal di folder prosesnya, dan namanya unik per folder:
     * menyimpan membawa {@code folderId} di badan, mengalihkan dan menghapus
     * membawa {@code ?folderId=}. Tanpa itu, yang dimaksud adalah pemicu
     * bernama itu di mana pun ia berada (lihat CatalogService.pilihFolder).
     */
    @GetMapping
    public List<Map<String, Object>> daftar(@RequestParam(required = false) String folderId) {
        return service.daftar(CurrentUser.get().tenantId(), folders.saring(CurrentUser.get(), folderId));
    }

    @PostMapping
    public Map<String, Object> simpan(@RequestBody(required = false) Map<String, Object> body) {
        return service.simpan(CurrentUser.get().tenantId(), TriggerRequest.dari(body),
                folders.saring(CurrentUser.get(), Badan.teks(body, "folderId")), izin.penjaga(CurrentUser.get()));
    }

    @PostMapping("/{name}/toggle")
    public Map<String, Object> alihkan(@PathVariable String name,
                                       @RequestParam(required = false) String folderId) {
        return service.alihkan(CurrentUser.get().tenantId(), name, folders.saring(CurrentUser.get(), folderId));
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> hapus(@PathVariable String name,
                                     @RequestParam(required = false) String folderId) {
        service.hapus(CurrentUser.get().tenantId(), name, folders.saring(CurrentUser.get(), folderId));

        return Map.of("ok", true);
    }
}
