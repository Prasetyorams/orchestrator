package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.RequestBodies;

import java.util.Map;

/**
 * Laporan keadaan dari robot (POST /api/jobs/{id}/state), dibaca longgar.
 *
 * <p>{@code info} dan {@code outputJson} boleh null, dan itu BUKAN sama dengan
 * untai kosong: null berarti "tidak dilaporkan kali ini" dan nilai lamanya
 * dipertahankan, sedangkan untai kosong berarti "kosongkan". Robot melaporkan
 * kemajuan berkali-kali dan hanya mengisi sebagian medan tiap kali.
 */
public record UpdateJobStateRequest(String state, int progress, String info, String outputJson) {

    public static UpdateJobStateRequest fromBody(Map<String, Object> body) {
        return new UpdateJobStateRequest(
                RequestBodies.text(body, "state", ""),
                RequestBodies.integer(body, "progress", 0),
                RequestBodies.text(body, "info"),
                RequestBodies.text(body, "outputJson"));
    }
}
