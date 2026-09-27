package id.jakforge.openorchestrator.common;

/** Kata kunci pencarian menjadi pola LIKE/ILIKE yang aman. */
public final class LikePatterns {

    private LikePatterns() {
    }

    /**
     * "%kata%", dengan % dan _ di dalam kata kuncinya diloloskan.
     *
     * <p>Tanpa pelolosan, pencarian "100%" berubah menjadi "cocokkan apa saja".
     * Backslash diloloskan lebih dulu, kalau tidak pelolosan berikutnya akan
     * ikut terloloskan.
     */
    public static String containing(String keyword) {
        return "%" + keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }
}
