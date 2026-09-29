package id.jakforge.openorchestrator.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.IntSupplier;

/**
 * Memeriksa job Robot Agent tiap {@code openorchestrator.agent.monitor-interval}.
 *
 * <p>Lebih sering daripada penjadwal pemicu: lease penyiapan hanya beberapa
 * menit, dan job yang agentnya hilang harus terlihat di dasbor dalam hitungan
 * detik, bukan setengah menit kemudian.
 *
 * <p>Kegagalan satu langkah tidak menghentikan langkah lain.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMonitor {

    private final JobSupervisionService supervision;

    @Scheduled(initialDelayString = "${openorchestrator.scheduler.initial-delay}",
            fixedDelayString = "${openorchestrator.agent.monitor-interval}")
    public void runCycle() {
        run("lease penyiapan", supervision::expireLeases);
        run("agent tidak berdenyut", supervision::markUnresponsive);
        run("agent hilang", supervision::markLost);
        run("batas waktu", supervision::requestTimeoutStops);
    }

    private static void run(String step, IntSupplier action) {
        try {
            action.getAsInt();
        } catch (Exception e) {
            log.error("Pemantau Robot Agent gagal pada langkah {}.", step, e);
        }
    }
}
