package id.jakforge.openorchestrator.model;

/**
 * Membandingkan versi Robot Agent, mis. "1.2.10" lawan "1.2.9".
 *
 * <p>Angka per bagian, bukan urutan teks: "1.10.0" lebih baru daripada
 * "1.9.0", sedangkan perbandingan teks mengatakan sebaliknya. Bagian yang
 * tidak ada dianggap 0 ("1.2" = "1.2.0"); akhiran seperti "-beta" atau
 * "+build" diabaikan.
 */
public final class AgentVersions {

    private AgentVersions() {
    }

    /** Apakah {@code version} lebih lama daripada {@code minimum}. Versi yang tidak bisa dibaca dianggap lebih lama. */
    public static boolean isOlderThan(String version, String minimum) {
        if (minimum == null || minimum.isBlank()) return false;
        if (version == null || version.isBlank()) return true;

        int[] actual = parse(version);
        int[] required = parse(minimum);

        if (actual == null) return true;
        if (required == null) return false;

        for (int i = 0; i < Math.max(actual.length, required.length); i++) {
            int a = i < actual.length ? actual[i] : 0;
            int r = i < required.length ? required[i] : 0;

            if (a != r) return a < r;
        }

        return false;
    }

    private static int[] parse(String version) {
        String core = version.trim().split("[-+ ]", 2)[0];
        if (core.startsWith("v") || core.startsWith("V")) core = core.substring(1);

        String[] parts = core.split("\\.");
        int[] numbers = new int[parts.length];

        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return numbers;
    }
}
