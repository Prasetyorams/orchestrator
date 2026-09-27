package id.jakforge.forgehub.dto.response;

import java.util.List;
import java.util.Map;

/** Isi lonceng di bilah atas: jumlah yang belum dibaca dan beberapa yang terbaru. */
public record AlertSummaryResponse(long unread, List<Map<String, Object>> recent) {
}
