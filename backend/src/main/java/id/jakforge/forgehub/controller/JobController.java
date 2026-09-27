package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.dto.request.CreateJobRequest;
import id.jakforge.forgehub.dto.request.UpdateJobStateRequest;
import id.jakforge.forgehub.dto.response.CreatedResponse;
import id.jakforge.forgehub.dto.response.NextJobResponse;
import id.jakforge.forgehub.dto.response.OkResponse;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.JobService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * <p>Membuat pekerjaan, mengambil yang berikutnya, dan melaporkan keadaannya
 * dipanggil Studio dan JakRunner, jadi badannya dibaca longgar.
 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    /** Tanpa {@code folderId}: pekerjaan seluruh penyewa. */
    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                             @RequestParam(required = false) String state,
                                             @RequestParam(required = false) String process,
                                             @RequestParam(required = false) String folderId,
                                             @RequestParam(required = false) Integer limit) {
        return jobService.findAll(principal, state, process, folderId, limit);
    }

    /**
     * Dipetakan SEBELUM {@code /{id}}.
     *
     * <p>Spring memang memilih pola yang lebih spesifik lebih dulu, tapi
     * mengandalkan itu diam-diam membuat urutan penulisan menjadi penting tanpa
     * alasan yang terlihat. Ditulis di atas supaya niatnya jelas terbaca.
     */
    @GetMapping("/next")
    public NextJobResponse claimNext(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                     @RequestParam(required = false) String robot) {
        return jobService.claimNext(principal, robot);
    }

    @GetMapping("/{id}")
    public Map<String, Object> findById(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                        @PathVariable String id) {
        return jobService.findById(principal, id);
    }

    @PostMapping
    public CreatedResponse create(@AuthenticationPrincipal ForgeHubPrincipal principal,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return jobService.create(principal, CreateJobRequest.fromBody(body));
    }

    @PostMapping("/{id}/state")
    public OkResponse updateState(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        jobService.updateState(principal, id, UpdateJobStateRequest.fromBody(body));

        return OkResponse.success();
    }

    @PostMapping("/{id}/stop")
    public OkResponse requestStop(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String id) {
        jobService.requestStop(principal, id);

        return OkResponse.success();
    }

    @DeleteMapping("/{id}")
    public OkResponse delete(@AuthenticationPrincipal ForgeHubPrincipal principal, @PathVariable String id) {
        jobService.delete(principal, id);

        return OkResponse.success();
    }
}
