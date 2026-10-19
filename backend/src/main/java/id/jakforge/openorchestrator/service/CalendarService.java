package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Cron;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.dto.request.SaveCalendarRequest;
import id.jakforge.openorchestrator.dto.response.CreatedResponse;
import id.jakforge.openorchestrator.repository.CalendarRepository;
import id.jakforge.openorchestrator.repository.CalendarRepository.CalendarDay;
import id.jakforge.openorchestrator.repository.TriggerRepository;
import id.jakforge.openorchestrator.repository.TriggerRepository.CalendarTrigger;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Kalender hari non-kerja (V16), seperti Non-Working Days di UiPath
 * Orchestrator: daftar tanggal saat pemicu yang memakai kalender itu tidak
 * berjalan.
 *
 * <p>Setiap perubahan tanggal langsung menghitung ulang waktu jalan berikutnya
 * pemicu yang memakainya, supaya "Jalan Berikutnya" di dasbor tidak menunjuk
 * hari yang baru saja dijadikan libur.
 */
@Service
@RequiredArgsConstructor
public class CalendarService {

    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_DESCRIPTION_LENGTH = 400;
    static final int MAX_NOTE_LENGTH = 200;
    /** Sepuluh tahun hari libur setiap hari — lebih dari itu pasti salah tempel. */
    static final int MAX_DAYS = 3660;

    private static final String NOT_FOUND = "Kalender tidak ada.";

    private final CalendarRepository calendarRepository;
    private final TriggerRepository triggerRepository;
    private final NextRunCalculator nextRunCalculator;

    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal) {
        return calendarRepository.findAll(principal.tenantId());
    }

    /** Satu kalender beserta seluruh tanggalnya dan pemicu yang memakainya. */
    public Map<String, Object> find(OpenOrchestratorPrincipal principal, String idText) {
        UUID tenantId = principal.tenantId();
        UUID id = parseId(idText);

        Map<String, Object> calendar = new LinkedHashMap<>(calendarRepository.find(tenantId, id)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND)));

        calendar.put("days", calendarRepository.findDays(id).stream()
                .map(d -> {
                    Map<String, Object> day = new LinkedHashMap<>();
                    day.put("date", d.day().toString());
                    day.put("note", d.note());
                    return day;
                })
                .toList());
        calendar.put("triggers", calendarRepository.findTriggerNames(tenantId, id));

        return calendar;
    }

    @Transactional
    public CreatedResponse create(OpenOrchestratorPrincipal principal, SaveCalendarRequest request) {
        UUID tenantId = principal.tenantId();
        List<CalendarDay> days = validate(tenantId, null, request);

        UUID id = UUID.randomUUID();
        calendarRepository.insert(id, tenantId, request.name(), request.description());
        calendarRepository.replaceDays(id, days);

        return CreatedResponse.of(id);
    }

    @Transactional
    public void update(OpenOrchestratorPrincipal principal, String idText, SaveCalendarRequest request) {
        UUID tenantId = principal.tenantId();
        UUID id = parseId(idText);

        if (!calendarRepository.exists(tenantId, id)) throw ApiException.notFound(NOT_FOUND);

        List<CalendarDay> days = validate(tenantId, id, request);

        calendarRepository.update(tenantId, id, request.name(), request.description());
        calendarRepository.replaceDays(id, days);

        rescheduleTriggers(tenantId, id, days.stream().map(CalendarDay::day).collect(Collectors.toSet()));
    }

    /**
     * Kalender yang masih dipakai pemicu ditolak 409 dengan menyebut pemicunya:
     * menghapusnya diam-diam membuat pemicu itu mulai berjalan di hari libur.
     */
    @Transactional
    public void delete(OpenOrchestratorPrincipal principal, String idText) {
        UUID tenantId = principal.tenantId();
        UUID id = parseId(idText);

        List<String> users = calendarRepository.findTriggerNames(tenantId, id);
        if (!users.isEmpty()) {
            String shown = String.join(", ", users.subList(0, Math.min(5, users.size())))
                    + (users.size() > 5 ? ", dan " + (users.size() - 5) + " lainnya" : "");
            throw ApiException.conflict("Kalender ini masih dipakai pemicu: " + shown
                    + ". Lepaskan dulu dari pemicunya.").withCode("CALENDAR_IN_USE");
        }

        if (calendarRepository.delete(tenantId, id) == 0) throw ApiException.notFound(NOT_FOUND);
    }

    /**
     * Hitung ulang waktu jalan pemicu aktif yang memakai kalender itu.
     *
     * <p>Cron selalu dihitung ulang: tanggal yang baru dijadikan libur harus
     * dilompati, dan tanggal yang dikembalikan menjadi hari kerja boleh dipakai
     * lagi. Pemicu selang hanya kalau jadwalnya kini jatuh di hari libur, dan
     * ia melanjutkan pukul 00:00 hari kerja sesudahnya — menghitungnya ulang
     * dari "sekarang" bisa memajukannya ke sebelum jadwal semula.
     */
    void rescheduleTriggers(UUID tenantId, UUID calendarId, Set<LocalDate> nonWorkingDays) {
        for (CalendarTrigger trigger : triggerRepository.findEnabledByCalendar(tenantId, calendarId)) {
            ZoneId zone = Cron.zoneOrUtc(trigger.timezone());
            boolean usesCron = trigger.cron() != null && !trigger.cron().isBlank();
            boolean onHoliday = NextRunCalculator.isNonWorkingDay(trigger.nextRunAt(), zone, nonWorkingDays);

            if (!usesCron && !onHoliday) continue;

            OffsetDateTime next = usesCron
                    ? nextRunCalculator.nextRun(trigger.cron(), trigger.intervalMinutes(), zone, nonWorkingDays)
                    : NextRunCalculator.startOfNextWorkingDay(trigger.nextRunAt(), zone, nonWorkingDays);

            // Null (tidak ada hari kerja lagi) dibiarkan: penjadwal yang
            // mematikan pemicunya dengan peringatan, sesuai aturan yang sama.
            if (next != null && !Objects.equals(next, trigger.nextRunAt())) {
                triggerRepository.updateNextRun(trigger.id(), next);
            }
        }
    }

    /** Periksa isi permintaan; tanggal kembar digabung, urut menurut tanggal. */
    private List<CalendarDay> validate(UUID tenantId, UUID id, SaveCalendarRequest request) {
        if (request.name() == null) throw ApiException.badRequest("Nama kalender wajib diisi.");
        if (request.name().length() > MAX_NAME_LENGTH) {
            throw ApiException.badRequest("Nama kalender paling banyak " + MAX_NAME_LENGTH + " karakter.");
        }
        if (request.description() != null && request.description().length() > MAX_DESCRIPTION_LENGTH) {
            throw ApiException.badRequest("Keterangan kalender paling banyak " + MAX_DESCRIPTION_LENGTH + " karakter.");
        }
        if (calendarRepository.nameTaken(tenantId, request.name(), id)) {
            throw ApiException.conflict("Kalender '" + request.name() + "' sudah ada.");
        }
        if (request.days().size() > MAX_DAYS) {
            throw ApiException.badRequest("Paling banyak " + MAX_DAYS + " tanggal per kalender.");
        }

        TreeMap<LocalDate, String> days = new TreeMap<>();
        for (SaveCalendarRequest.Day day : request.days()) {
            if (day == null || day.date() == null || day.date().isBlank()) {
                throw ApiException.badRequest("Tanggal wajib diisi.");
            }

            LocalDate date;
            try {
                date = LocalDate.parse(day.date().trim());
            } catch (DateTimeParseException e) {
                throw ApiException.badRequest("Tanggal tidak sah: '" + day.date() + "'. Bentuknya tahun-bulan-tanggal, mis. 2026-12-25.");
            }

            String note = day.note() == null || day.note().isBlank() ? null : day.note().trim();
            if (note != null && note.length() > MAX_NOTE_LENGTH) {
                throw ApiException.badRequest("Catatan tanggal " + date + " paling banyak " + MAX_NOTE_LENGTH + " karakter.");
            }

            // Kembar: catatan yang terisi menang atas yang kosong.
            days.merge(date, note == null ? "" : note, (old, neu) -> neu.isEmpty() ? old : neu);
        }

        return days.entrySet().stream()
                .map(e -> new CalendarDay(e.getKey(), e.getValue().isEmpty() ? null : e.getValue()))
                .toList();
    }

    private static UUID parseId(String idText) {
        UUID id = Uuids.parseOrNull(idText);
        if (id == null) throw ApiException.notFound(NOT_FOUND);
        return id;
    }
}
