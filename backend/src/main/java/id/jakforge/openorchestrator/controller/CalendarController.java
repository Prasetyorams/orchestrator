package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.dto.request.SaveCalendarRequest;
import id.jakforge.openorchestrator.dto.response.CreatedResponse;
import id.jakforge.openorchestrator.dto.response.OkResponse;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import id.jakforge.openorchestrator.service.CalendarService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Kalender hari non-kerja untuk pemicu (V16), seperti Non-Working Days di UiPath. */
@RestController
@RequestMapping("/api/calendars")
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarService calendarService;

    @GetMapping
    public List<Map<String, Object>> findAll(@AuthenticationPrincipal OpenOrchestratorPrincipal principal) {
        return calendarService.findAll(principal);
    }

    @GetMapping("/{id}")
    public Map<String, Object> find(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String id) {
        return calendarService.find(principal, id);
    }

    @PostMapping
    public CreatedResponse create(@AuthenticationPrincipal OpenOrchestratorPrincipal principal,
                                  @Valid @RequestBody SaveCalendarRequest request) {
        return calendarService.create(principal, request);
    }

    /** Mengganti nama, keterangan, dan SELURUH daftar tanggalnya. */
    @PutMapping("/{id}")
    public OkResponse update(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String id,
                             @Valid @RequestBody SaveCalendarRequest request) {
        calendarService.update(principal, id, request);

        return OkResponse.success();
    }

    @DeleteMapping("/{id}")
    public OkResponse delete(@AuthenticationPrincipal OpenOrchestratorPrincipal principal, @PathVariable String id) {
        calendarService.delete(principal, id);

        return OkResponse.success();
    }
}
