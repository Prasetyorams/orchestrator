package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import id.jakforge.openorchestrator.repository.JobRepository;
import id.jakforge.openorchestrator.repository.MachineRepository;
import id.jakforge.openorchestrator.security.MachineKeys;
import id.jakforge.openorchestrator.security.OpenOrchestratorPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pemeriksaan untuk token agent dan executor, sebelum controller berjalan.
 *
 * <p>Token JWT sah sampai kedaluwarsa, dan tidak bisa ditarik kembali. Dua
 * keadaan harus memutusnya lebih cepat:
 *
 * <ul>
 *   <li>machine key diganti atau dicabut — agent dengan kunci lama berhenti
 *       SEKARANG, bukan satu jam lagi;</li>
 *   <li>job selesai — token executor hanya berlaku selama job-nya dipegang
 *       robot, supaya token yang tertinggal di mesin tidak bisa dipakai lagi.</li>
 * </ul>
 *
 * <p>Status job executor diingat beberapa detik: satu workflow bisa memanggil
 * puluhan activity per detik, dan setiap panggilan tidak perlu menjadi kueri.
 */
@Service
@RequiredArgsConstructor
public class AgentAccessService {

    static final Duration EXECUTOR_CACHE = Duration.ofSeconds(5);

    private record CachedHold(boolean held, Instant expiresAt) {
    }

    private final MachineRepository machineRepository;
    private final JobRepository jobRepository;
    private final Clock clock;

    private final Map<UUID, CachedHold> executorJobs = new ConcurrentHashMap<>();

    /** 401 kalau kunci yang dipakai agent masuk sudah diganti atau dicabut. */
    public void requireCurrentKey(OpenOrchestratorPrincipal agent) {
        String current = machineRepository.findKeyHash(agent.machineId()).map(MachineKeys::keyIdOf).orElse(null);

        if (current == null || !Objects.equals(current, agent.keyId())) {
            throw ApiException.unauthorized("Machine key mesin ini sudah diganti atau dicabut. Pasang kunci yang baru.")
                    .withCode("MachineKeyRevoked");
        }
    }

    /** Agent boleh mengunduh paket yang sedang dijalankan job robot di mesinnya. */
    public boolean canDownloadPackage(OpenOrchestratorPrincipal agent, String name, String version) {
        return name != null && version != null && jobRepository.machineRunsPackage(agent.machineId(), name, version);
    }

    /** 401 kalau job executor ini sudah selesai. */
    public void requireActiveExecutor(OpenOrchestratorPrincipal executor) {
        Instant now = clock.instant();
        CachedHold cached = executorJobs.get(executor.jobId());

        boolean held;

        if (cached != null && cached.expiresAt().isAfter(now)) {
            held = cached.held();
        } else {
            held = jobRepository.isHeld(executor.tenantId(), executor.jobId());
            executorJobs.put(executor.jobId(), new CachedHold(held, now.plus(EXECUTOR_CACHE)));

            // Yang sudah lewat dibuang sesekali supaya peta ini tidak tumbuh selamanya.
            if (executorJobs.size() > 10_000) executorJobs.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        }

        if (!held) {
            throw ApiException.unauthorized("Token executor sudah tidak berlaku: pekerjaannya sudah selesai.")
                    .withCode("ExecutorTokenExpired");
        }
    }
}
