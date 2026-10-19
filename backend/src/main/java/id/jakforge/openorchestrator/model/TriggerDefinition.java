package id.jakforge.openorchestrator.model;

import java.util.UUID;

/**
 * Isi sebuah pemicu yang disimpan: apa yang dijalankan, kapan, dan dengan apa.
 *
 * <p>Satu record, bukan sepuluh parameter lepas: menyimpan dan memperbarui
 * pemicu menulis kolom yang sama, dan urutan sepuluh argumen bertipe untai
 * adalah tempat paling mudah untuk salah pasang tanpa ketahuan pengompilasi.
 *
 * @param calendarId kalender hari non-kerja (V16); null berarti tanpa kalender
 */
public record TriggerDefinition(
        String name,
        String processName,
        String robotName,
        String type,
        String cron,
        int intervalMinutes,
        boolean enabled,
        String priority,
        String timezone,
        String runtimeType,
        UUID calendarId) {
}
