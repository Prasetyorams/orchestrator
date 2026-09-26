package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.common.Badan;
import id.jakforge.forgehub.dto.JobRequest;
import id.jakforge.forgehub.dto.JobStateRequest;
import id.jakforge.forgehub.security.CurrentUser;
import id.jakforge.forgehub.service.FolderService;
import id.jakforge.forgehub.service.JobService;
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

/**
 * Pekerjaan.
 *
 * <p>Controller di sini sengaja setipis mungkin: membaca permintaan, memanggil
 * service, mengembalikan hasilnya. Tidak ada SQL, tidak ada aturan bisnis,
 * tidak ada penanganan galat — galat dilempar service sebagai ApiException dan
 * diterjemahkan menjadi status HTTP oleh satu penangan bersama.
 *
 * <p>Yang tetap ada di sini hanyalah hal yang benar-benar urusan HTTP: bentuk
 * jalur, nama parameter kueri, dan cara badan permintaan dibaca.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService service;
    private final FolderService folders;

    public JobController(JobService service, FolderService folders) {
        this.service = service;
        this.folders = folders;
    }

    /** Tanpa {@code folderId}: pekerjaan seluruh penyewa. */
    @GetMapping
    public List<Map<String, Object>> daftar(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String process,
            @RequestParam(required = false) String folderId,
            @RequestParam(required = false) Integer limit) {

        return service.daftar(CurrentUser.get().tenantId(), state, process,
                folders.saring(CurrentUser.get(), folderId), limit);
    }

    /**
     * Dipetakan SEBELUM {@code /{id}}.
     *
     * <p>Spring memang memilih pola yang lebih spesifik lebih dulu, tapi
     * mengandalkan itu diam-diam membuat urutan penulisan menjadi penting tanpa
     * alasan yang terlihat. Ditulis di atas supaya niatnya jelas terbaca.
     */
    @GetMapping("/next")
    public Map<String, Object> berikutnya(@RequestParam(required = false) String robot) {
        return service.ambilBerikutnya(CurrentUser.get().tenantId(), robot);
    }

    @GetMapping("/{id}")
    public Map<String, Object> satu(@PathVariable String id) {
        return service.satu(CurrentUser.get().tenantId(), id);
    }

    /**
     * {@code folderId} di badan menyebut folder prosesnya; nama proses unik
     * per folder. Studio tidak mengirimnya — lihat CatalogService.pilihFolder.
     */
    @PostMapping
    public Map<String, Object> buat(@RequestBody(required = false) Map<String, Object> body) {
        return service.buat(CurrentUser.get().tenantId(), JobRequest.dari(body),
                folders.saring(CurrentUser.get(), Badan.teks(body, "folderId")));
    }

    @PostMapping("/{id}/state")
    public Map<String, Object> keadaan(@PathVariable String id,
                                       @RequestBody(required = false) Map<String, Object> body) {

        service.ubahKeadaan(CurrentUser.get().tenantId(), id, JobStateRequest.dari(body));

        return Map.of("ok", true);
    }

    @PostMapping("/{id}/stop")
    public Map<String, Object> hentikan(@PathVariable String id) {
        service.hentikan(CurrentUser.get().tenantId(), id);

        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> hapus(@PathVariable String id) {
        service.hapus(CurrentUser.get().tenantId(), id);

        return Map.of("ok", true);
    }
}
