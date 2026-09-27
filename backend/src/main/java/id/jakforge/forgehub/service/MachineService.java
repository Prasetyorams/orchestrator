package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.request.CreateMachineRequest;
import id.jakforge.forgehub.repository.MachineRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
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

    public List<Map<String, Object>> findAll(ForgeHubPrincipal principal) {
        return machineRepository.findAll(principal.tenantId());
    }

    @Transactional
    public void create(ForgeHubPrincipal principal, CreateMachineRequest request) {
        if (machineRepository.existsByName(principal.tenantId(), request.name())) {
            throw ApiException.conflict("Mesin '" + request.name() + "' sudah ada.");
        }

        machineRepository.insert(principal.tenantId(), request.name(), request.type(), request.licenseKey(),
                request.description());
    }

    public void delete(ForgeHubPrincipal principal, String name) {
        if (machineRepository.deleteByName(principal.tenantId(), name) == 0) {
            throw ApiException.notFound("Mesin '" + name + "' tidak ada.");
        }
    }
}
