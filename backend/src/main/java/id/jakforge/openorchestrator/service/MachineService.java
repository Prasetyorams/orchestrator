package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.dto.request.CreateMachineRequest;
import id.jakforge.openorchestrator.dto.request.UpdateMachineRequest;
import id.jakforge.openorchestrator.dto.response.MachineKeyResponse;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.security.MachineKeys;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Aturan tentang mesin tempat robot berjalan, dan machine key Robot Agent-nya. */
@Service
@RequiredArgsConstructor
public class MachineService {

    private final MachineRepository machineRepository;
    private final OpenOrchestratorProperties properties;

    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal) {
        return machineRepository.findAll(principal.tenantId(), properties.agent().offlineAfter().toSeconds());
    }

    @Transactional
    public void create(OpenOrchestratorPrincipal principal, CreateMachineRequest request) {
        if (machineRepository.existsByName(principal.tenantId(), request.name())) {
            throw ApiException.conflict("Mesin '" + request.name() + "' sudah ada.");
        }

        machineRepository.insert(principal.tenantId(), request.name(), request.type(), request.licenseKey(),
                request.description());
    }

    @Transactional
    public void update(OpenOrchestratorPrincipal principal, String name, UpdateMachineRequest request) {
        if (machineRepository.updateSettings(principal.tenantId(), name, request.type(), request.description(),
                request.slots(), request.leaseSeconds()) == 0) {
            throw machineNotFound(name);
        }
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
