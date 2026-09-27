package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.request.CreateEnvironmentRequest;
import id.jakforge.forgehub.repository.EnvironmentRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
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

    public List<Map<String, Object>> findAll(ForgeHubPrincipal principal) {
        return environmentRepository.findAll(principal.tenantId());
    }

    @Transactional
    public void create(ForgeHubPrincipal principal, CreateEnvironmentRequest request) {
        if (environmentRepository.existsByName(principal.tenantId(), request.name())) {
            throw ApiException.conflict("Lingkungan '" + request.name() + "' sudah ada.");
        }

        environmentRepository.insert(principal.tenantId(), request.name(), request.description());
    }

    public void delete(ForgeHubPrincipal principal, String name) {
        if (environmentRepository.deleteByName(principal.tenantId(), name) == 0) {
            throw ApiException.notFound("Lingkungan '" + name + "' tidak ada.");
        }
    }
}
