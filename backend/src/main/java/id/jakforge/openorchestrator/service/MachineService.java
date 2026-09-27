package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.dto.request.CreateMachineRequest;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Aturan tentang mesin tempat robot berjalan. */
@Service
@RequiredArgsConstructor
public class MachineService {

    private final MachineRepository machineRepository;

    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal) {
        return machineRepository.findAll(principal.tenantId());
    }

    @Transactional
    public void create(OpenOrchestratorPrincipal principal, CreateMachineRequest request) {
        if (machineRepository.existsByName(principal.tenantId(), request.name())) {
            throw ApiException.conflict("Mesin '" + request.name() + "' sudah ada.");
        }

        machineRepository.insert(principal.tenantId(), request.name(), request.type(), request.licenseKey(),
                request.description());
    }

    public void delete(OpenOrchestratorPrincipal principal, String name) {
        if (machineRepository.deleteByName(principal.tenantId(), name) == 0) {
            throw ApiException.notFound("Mesin '" + name + "' tidak ada.");
        }
    }
}
