package id.jakforge.openorchestrator.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Penyapu butir antrean tiap {@code openorchestrator.queues.sweep-interval}:
 * butir yang terlalu lama IN_PROGRESS tanpa hasil menjadi ABANDONED.
 *
 * <p>Terpisah dari AgentMonitor: batasnya berjam-jam, jadi tidak perlu
 * diperiksa tiap sepuluh detik.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueMonitor {

    private final QueueService queueService;

    @Scheduled(initialDelayString = "${openorchestrator.scheduler.initial-delay}",
            fixedDelayString = "${openorchestrator.queues.sweep-interval}")
    public void runCycle() {
        try {
            int abandoned = queueService.abandonStaleItems();
            if (abandoned > 0) log.info("{} butir antrean yang tertahan ditandai ABANDONED.", abandoned);
        } catch (Exception e) {
            log.error("Penyapu butir antrean gagal.", e);
        }
    }
}
