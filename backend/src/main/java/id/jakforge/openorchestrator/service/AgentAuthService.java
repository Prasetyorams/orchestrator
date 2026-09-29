package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Timestamps;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.dto.request.AgentLoginRequest;
import id.jakforge.openorchestrator.dto.response.AgentLoginResponse;
import id.jakforge.openorchestrator.model.AgentVersions;
import id.jakforge.openorchestrator.model.Severity;
import id.jakforge.openorchestrator.repository.AlertRepository;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.repository.RobotRepository;
import id.jakforge.openorchestrator.security.JwtService;
import id.jakforge.openorchestrator.security.MachineKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Robot Agent masuk dengan machine key (POST /api/agent/login).
 *
 * <p>Jawaban yang sama untuk kunci salah, kunci dicabut, dan kunci yang tidak
 * pernah ada: yang menebak tidak perlu tahu mana yang hampir benar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAuthService {

    static final String INVALID_KEY_MESSAGE = "Machine key tidak sah atau sudah dicabut.";

    /** Batas baris per kiriman /api/agent/logs — ikut disebut di setelan supaya agent tahu. */
    public static final int MAX_LOG_LINES = 500;

    private static final String ALERT_SOURCE = "robots";

    private final MachineRepository machineRepository;
    private final RobotRepository robotRepository;
    private final AlertRepository alertRepository;
    private final JwtService jwtService;
    private final OpenOrchestratorProperties properties;

    @Transactional
    public AgentLoginResponse login(AgentLoginRequest request) {
        if (!MachineKeys.looksValid(request.machineKey())) throw invalidKey();

        String keyHash = MachineKeys.hash(request.machineKey());
        Map<String, Object> machine = machineRepository.findByKeyHash(keyHash).orElseThrow(AgentAuthService::invalidKey);

        OpenOrchestratorProperties.Agent agent = properties.agent();

        if (request.agentVersion() == null) {
            throw ApiException.badRequest("agentVersion wajib diisi.").withCode("AgentVersionMissing");
        }

        if (AgentVersions.isOlderThan(request.agentVersion(), agent.minVersion())) {
            throw new ApiException(HttpStatus.UPGRADE_REQUIRED,
                    "Versi Robot Agent " + request.agentVersion() + " terlalu lama; minimal " + agent.minVersion() + ".")
                    .withCode("AgentTooOld").withMinAgentVersion(agent.minVersion());
        }

        UUID machineId = Uuids.fromColumn(machine.get("id"));
        UUID tenantId = Uuids.fromColumn(machine.get("tenantId"));
        String machineName = (String) machine.get("name");

        warnIfKeyMoved(tenantId, machineName, (String) machine.get("agentHostName"), request.machineName());

        machineRepository.recordAgentLogin(machineId, request.agentVersion(), request.os(), request.machineName(),
                request.maxInteractiveSessions());

        var token = jwtService.issueAgentToken(machineId, tenantId, machineName, MachineKeys.keyIdOf(keyHash),
                agent.tokenTtl());

        int leaseSeconds = ((Number) machine.get("leaseSeconds")).intValue();
        Integer maxSessions = request.maxInteractiveSessions() != null
                ? request.maxInteractiveSessions()
                : (Integer) machine.get("maxInteractiveSessions");

        Map<String, Object> machineInfo = new LinkedHashMap<>();
        machineInfo.put("id", machineId.toString());
        machineInfo.put("name", machineName);
        machineInfo.put("slots", effectiveSlots(((Number) machine.get("slots")).intValue(), maxSessions));
        machineInfo.put("leaseSeconds", leaseSeconds);

        List<Map<String, Object>> robots = new ArrayList<>(robotRepository.findForMachine(machineId));

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("heartbeatSeconds", agent.heartbeatSeconds());
        settings.put("heartbeatBusySeconds", agent.heartbeatBusySeconds());
        settings.put("leaseSeconds", leaseSeconds);
        settings.put("minAgentVersion", agent.minVersion());
        settings.put("settingsVersion", machine.get("settingsVersion"));
        settings.put("maxLogLinesPerRequest", MAX_LOG_LINES);
        settings.put("maxAttachmentBytes", agent.maxAttachmentSize().toBytes());
        settings.put("maxAttachmentsPerJob", agent.maxAttachmentsPerJob());
        settings.put("maxOutputBytes", agent.maxOutputSize().toBytes());

        log.info("Robot Agent {} masuk dari mesin {} ({} robot).", request.agentVersion(), machineName, robots.size());

        return new AgentLoginResponse(token.token(), Timestamps.format(token.expiresAt()), machineInfo, robots,
                settings);
    }

    /**
     * Slot mesin, dibatasi jumlah sesi interaktif yang dilaporkan Windows:
     * Windows 10/11 hanya mengizinkan satu, berapa pun yang diisi admin.
     */
    public static int effectiveSlots(int slots, Integer maxInteractiveSessions) {
        return maxInteractiveSessions != null && maxInteractiveSessions > 0
                ? Math.min(slots, maxInteractiveSessions)
                : slots;
    }

    /**
     * Kunci yang tiba-tiba dipakai dari komputer lain patut diperiksa: bisa
     * mesin yang diganti namanya, bisa kunci yang disalin. Diperingatkan sekali
     * per perpindahan, bukan setiap kali masuk.
     */
    private void warnIfKeyMoved(UUID tenantId, String machineName, String lastHost, String reportedHost) {
        if (reportedHost == null || reportedHost.equalsIgnoreCase(machineName)) return;
        if (lastHost != null && lastHost.equalsIgnoreCase(reportedHost)) return;

        alertRepository.insert(tenantId, Severity.Warning, "Machine key dipakai dari komputer lain",
                "Kunci mesin " + machineName + " dipakai masuk dari komputer bernama " + reportedHost
                        + ". Kalau bukan Anda yang memindahkannya, buat kunci baru untuk mesin ini.", ALERT_SOURCE);
    }

    private static ApiException invalidKey() {
        return ApiException.unauthorized(INVALID_KEY_MESSAGE).withCode("InvalidMachineKey");
    }
}
