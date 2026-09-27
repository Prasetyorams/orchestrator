package id.jakforge.forgehub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Putaran penjadwal: menjalankan pemicu yang jatuh tempo, lalu menandai
 * pekerjaan yang robotnya menghilang.
 *
 * <p>Diperiksa tiap {@code forgehub.scheduler.interval} (30 detik). Selang
 * sependek itu terasa boros, tapi biayanya satu kueri berindeks — dan
 * alternatifnya, memeriksa tiap menit, membuat pemicu yang dipasang untuk
 * "tiap 1 menit" meleset separuh waktu.
 *
 * <p>Kelas ini sengaja TIDAK membawa {@code @Transactional}: pekerjaannya
 * dijalankan lewat bean lain ({@link TriggerFiringService}, {@link JobService}),
 * supaya transaksinya benar-benar berlaku. Metode bertransaksi yang dipanggil
 * dari kelasnya sendiri melewati proxy Spring dan berjalan TANPA transaksi —
 * dan kunci baris pemicu pun lepas seketika.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerScheduler {

    private final TriggerFiringService triggerFiringService;
    private final JobService jobService;

    /**
     * initialDelay memberi basis data dan Flyway waktu untuk selesai sebelum
     * pemeriksaan pertama. Tanpa itu, putaran pertama berebut dengan penyiapan
     * dan mencatat kegagalan yang bukan kegagalan.
     */
    @Scheduled(initialDelayString = "${forgehub.scheduler.initial-delay}",
            fixedDelayString = "${forgehub.scheduler.interval}")
    public void runCycle() {
        fireDueTriggers();
        failJobsOfDisconnectedRobots();
    }

    /**
     * Satu pemicu yang gagal tidak boleh menghentikan penjadwal selamanya, dan
     * tidak boleh menahan pemicu lain. Dicatat, lalu dicoba lagi pada putaran
     * berikutnya.
     */
    private void fireDueTriggers() {
        List<UUID> dueTriggerIds;

        try {
            dueTriggerIds = triggerFiringService.findDueTriggerIds();
        } catch (Exception e) {
            log.error("Penjadwal gagal saat menjalankan pemicu.", e);
            return;
        }

        for (UUID triggerId : dueTriggerIds) {
            try {
                triggerFiringService.fireIfDue(triggerId);
            } catch (Exception e) {
                log.error("Penjadwal gagal saat menjalankan pemicu {}.", triggerId, e);
            }
        }
    }

    private void failJobsOfDisconnectedRobots() {
        try {
            jobService.failJobsOfDisconnectedRobots();
        } catch (Exception e) {
            log.error("Penjadwal gagal saat memeriksa pekerjaan terputus.", e);
        }
    }
}
