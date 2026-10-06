package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.common.Uuids;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.dto.request.CreateMachineRequest;
import id.jakforge.openorchestrator.dto.request.UpdateMachineRequest;
import id.jakforge.openorchestrator.dto.response.MachineKeyResponse;
import id.jakforge.openorchestrator.model.MachineStates;
import id.jakforge.openorchestrator.model.RuntimeTypes;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.security.MachineKeys;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Aturan tentang mesin tempat robot berjalan, dan machine key Robot Agent-nya. */
@Service
@RequiredArgsConstructor
public class MachineService {

    private final MachineRepository machineRepository;
    private final OpenOrchestratorProperties properties;

    /** Setiap mesin beserta runtime-nya ({@code runtimes}: tipe → jumlah). */
    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal) {
        List<Map<String, Object>> machines = machineRepository.findAll(principal.tenantId(),
                properties.agent().offlineAfter().toSeconds(), properties.robot().heartbeatTimeout().toSeconds());
        Map<String, Map<String, Integer>> runtimes = machineRepository.findRuntimesForTenant(principal.tenantId());

        for (Map<String, Object> machine : machines) {
            machine.put("runtimes", runtimes.getOrDefault((String) machine.get("id"), Map.of()));
        }

        return machines;
    }

    @Transactional
    public void create(OpenOrchestratorPrincipal principal, CreateMachineRequest request) {
        if (machineRepository.existsByName(principal.tenantId(), request.name())) {
            throw ApiException.conflict("Mesin '" + request.name() + "' sudah ada.");
        }

        Map<String, Integer> runtimes = request.runtimes() == null ? null : normalizeRuntimes(request.runtimes());

        UUID machineId = machineRepository.insert(principal.tenantId(), request.name(), request.type(),
                request.licenseKey(), request.description());

        if (runtimes != null) machineRepository.replaceRuntimes(machineId, runtimes);
    }

    /**
     * Ubah setelan mesin. {@code runtimes} menggantikan seluruh runtime-nya;
     * {@code slots} lama tanpa {@code runtimes} berarti sekian runtime
     * Production — bentuk yang dikirim dasbor sebelum tipe runtime ada.
     */
    @Transactional
    public void update(OpenOrchestratorPrincipal principal, String name, UpdateMachineRequest request) {
        UUID tenantId = principal.tenantId();

        Map<String, Integer> runtimes = request.runtimes() != null ? normalizeRuntimes(request.runtimes())
                : request.slots() != null ? Map.of(RuntimeTypes.PRODUCTION, request.slots())
                : null;

        String state = request.state() == null ? null : MachineStates.parse(request.state()).orElseThrow(() ->
                ApiException.badRequest("Keadaan mesin tidak dikenal: '" + request.state()
                        + "'. Pilih Active, Maintenance, atau Disabled."));

        if (machineRepository.updateSettings(tenantId, name, request.type(), request.description(),
                request.leaseSeconds(), state) == 0) {
            throw machineNotFound(name);
        }

        if (runtimes != null) {
            UUID machineId = Uuids.parseOrNull(String.valueOf(machineRepository.findByName(tenantId, name)
                    .orElseThrow(() -> machineNotFound(name)).get("id")));
            machineRepository.replaceRuntimes(machineId, runtimes);
        }
    }

    /**
     * Tipe dibakukan dan diperiksa; urutan katalog. Tipe yang tidak dikenal
     * DITOLAK, bukan diabaikan: "Produksi" yang diam-diam hilang berarti mesin
     * yang tidak menjalankan apa pun tanpa ada yang tahu sebabnya.
     */
    static Map<String, Integer> normalizeRuntimes(Map<String, Integer> requested) {
        Map<String, Integer> byType = new HashMap<>();

        for (Map.Entry<String, Integer> entry : requested.entrySet()) {
            String type = RuntimeTypes.parse(entry.getKey()).orElseThrow(() -> ApiException.badRequest(
                    "Tipe runtime tidak dikenal: '" + entry.getKey() + "'. Pilih Production, Testing, atau Development."));
            int count = entry.getValue() == null ? 0 : entry.getValue();

            if (count < 0 || count > RuntimeTypes.MAX_SLOTS) {
                throw ApiException.badRequest("Jumlah runtime " + type + " harus 0 sampai " + RuntimeTypes.MAX_SLOTS + ".");
            }

            byType.put(type, count);
        }

        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String type : RuntimeTypes.ALL) {
            if (byType.getOrDefault(type, 0) > 0) ordered.put(type, byType.get(type));
        }

        return ordered;
    }

    /**
     * Buat machine key baru. Kunci lama — kalau ada — langsung tidak berlaku,
     * termasuk token agent yang sedang memakainya.
     *
     * <p>Kuncinya dikembalikan SEKALI ini saja; yang tersimpan hanya hash-nya.
     */
    @Transactional
    public MachineKeyResponse createKey(OpenOrchestratorPrincipal principal, String name) {
        MachineKeys.Generated key = MachineKeys.generate();

        if (machineRepository.setKey(principal.tenantId(), name, key.hash(), key.displayPrefix()) == 0) {
            throw machineNotFound(name);
        }

        return new MachineKeyResponse(key.key(), key.displayPrefix());
    }

    /** Cabut kunci: agent mesin ini berhenti saat itu juga. */
    @Transactional
    public void revokeKey(OpenOrchestratorPrincipal principal, String name) {
        if (machineRepository.clearKey(principal.tenantId(), name) == 0) {
            if (!machineRepository.existsByName(principal.tenantId(), name)) throw machineNotFound(name);
            throw ApiException.badRequest("Mesin '" + name + "' belum punya machine key.");
        }
    }

    public void delete(OpenOrchestratorPrincipal principal, String name) {
        if (machineRepository.deleteByName(principal.tenantId(), name) == 0) {
            throw machineNotFound(name);
        }
    }

    private static ApiException machineNotFound(String name) {
        return ApiException.notFound("Mesin '" + name + "' tidak ada.");
    }
}
