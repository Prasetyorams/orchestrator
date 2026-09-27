package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.dto.request.CreateEnvironmentRequest;
import id.jakforge.openorchestrator.repository.EnvironmentRepository;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Aturan tentang lingkungan tempat robot dikelompokkan. */
@Service
@RequiredArgsConstructor
public class EnvironmentService {

    private final EnvironmentRepository environmentRepository;

    public List<Map<String, Object>> findAll(OpenOrchestratorPrincipal principal) {
        return environmentRepository.findAll(principal.tenantId());
    }

    @Transactional
    public void create(OpenOrchestratorPrincipal principal, CreateEnvironmentRequest request) {
        if (environmentRepository.existsByName(principal.tenantId(), request.name())) {
            throw ApiException.conflict("Lingkungan '" + request.name() + "' sudah ada.");
        }

        environmentRepository.insert(principal.tenantId(), request.name(), request.description());
    }

    public void delete(OpenOrchestratorPrincipal principal, String name) {
        if (environmentRepository.deleteByName(principal.tenantId(), name) == 0) {
            throw ApiException.notFound("Lingkungan '" + name + "' tidak ada.");
        }
    }
}
