package id.jakforge.openorchestrator.dto.response;

import java.util.List;
import java.util.Map;

/**
 * Jawaban denyut robot v1: waktu server, supaya robot bisa melihat selisih
 * jamnya, dan perintah untuknya ({@code heartbeat.commands}) — saat ini
 * StopJob untuk job yang diminta berhenti dari dasbor.
 *
 * <p>JakRunner lama mengabaikan {@code commands}; robot yang mengenalinya
 * menghentikan job itu tanpa harus menanyakan keadaannya berulang-ulang.
 */
public record HeartbeatResponse(boolean ok, String serverTime, List<Map<String, Object>> commands) {

    public static HeartbeatResponse at(String serverTime) {
        return new HeartbeatResponse(true, serverTime, List.of());
    }
}
