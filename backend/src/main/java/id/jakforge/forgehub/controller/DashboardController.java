package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.Izin;
import id.jakforge.forgehub.service.DashboardService;
import id.jakforge.forgehub.service.FolderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Halaman utama dan pencarian menyeluruh. */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService service;
    private final FolderService folders;
    private final Izin izin;

    public DashboardController(DashboardService service, FolderService folders, Izin izin) {
        this.service = service;
        this.folders = folders;
        this.izin = izin;
    }

    /**
     * Angka keempat periode — hari, minggu, bulan, tahun ini — ada di
     * {@code periods}. Tanpa {@code folderId}: seluruh penyewa.
     */
    @GetMapping("/dashboard")
    public Map<String, Object> dasbor(@RequestParam(required = false) String folderId) {
        ForgeHubPrincipal p = CurrentUser.get();

        return service.dasbor(p.tenantId(), folders.saring(p, folderId));
    }

    /** Tanpa {@code period}: empat belas hari terakhir, bentuk lamanya. */
    @GetMapping("/dashboard/history")
    public List<Map<String, Object>> riwayat(@RequestParam(required = false) String period) {
        return service.riwayat(CurrentUser.get().tenantId(), period);
    }

    /**
     * Hasil yang tidak boleh DIBACA peran pemintanya dibuang: pencarian tidak
     * boleh menjadi jalan memutar untuk melihat nama aset atau robot yang
     * halamannya sendiri tertutup baginya. Jenis hasil ("page") sama dengan
     * nama sumber izinnya; folder terlihat bagi siapa pun yang boleh membukanya.
     */
    @GetMapping("/search")
    public List<Map<String, Object>> cari(@RequestParam(name = "q", required = false) String q) {
        ForgeHubPrincipal p = CurrentUser.get();

        return service.cari(p.tenantId(), q, folders.akses(p)).stream()
                .filter(h -> "folders".equals(h.get("page")) || izin.boleh(p, h.get("page") + ".read"))
                .toList();
    }
}
