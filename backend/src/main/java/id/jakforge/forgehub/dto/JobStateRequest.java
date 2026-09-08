package id.jakforge.forgehub.dto;

import id.jakforge.forgehub.common.Badan;

import java.util.Map;

/**
 * Laporan keadaan dari robot.
 *
 * <p>{@code info} dan {@code outputJson} boleh null, dan itu BUKAN sama dengan
 * untai kosong: null berarti "tidak dilaporkan kali ini" dan nilai lamanya
 * dipertahankan, sedangkan untai kosong berarti "kosongkan". Robot melaporkan
 * kemajuan berkali-kali dan hanya mengisi sebagian medan tiap kali.
 */
public record JobStateRequest(String state, int progress, String info, String outputJson) {

    public static JobStateRequest dari(Map<String, Object> body) {
        return new JobStateRequest(
                Badan.teks(body, "state", ""),
                Badan.bulat(body, "progress", 0),
                Badan.teks(body, "info"),
                Badan.teks(body, "outputJson"));
    }
}
