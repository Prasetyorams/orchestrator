package id.jakforge.openorchestrator.model;

import java.util.Set;

/**
 * Resolusi layar sesi robot unattended (V13), seperti Resolution Width,
 * Height, dan Depth di UiPath. Robot Agent membuat sesi RDP loopback dengan
 * ukuran ini; 0 berarti bawaan agent (1024x768, kedalaman bawaan klien RDP).
 *
 * <p>Lebar dan tinggi berpasangan: dua-duanya 0, atau dua-duanya
 * {@value #MIN_SIZE}..{@value #MAX_SIZE}. Kedalaman 0, 15, 16, 24, atau 32 —
 * sama dengan pemeriksaan di agent dan CHECK di basis data.
 */
public record RobotResolution(int width, int height, int depth) {

    public static final RobotResolution DEFAULT = new RobotResolution(0, 0, 0);

    static final int MIN_SIZE = 200;
    static final int MAX_SIZE = 8192;
    static final Set<Integer> DEPTHS = Set.of(0, 15, 16, 24, 32);

    /** Nilai yang dikirim di atas yang lama; null berarti tidak diubah. */
    public RobotResolution with(Integer newWidth, Integer newHeight, Integer newDepth) {
        return new RobotResolution(newWidth != null ? newWidth : width, newHeight != null ? newHeight : height,
                newDepth != null ? newDepth : depth);
    }

    /** Pesan galat untuk orang yang mengisinya, atau null kalau sah. */
    public String problem() {
        boolean defaultSize = width == 0 && height == 0;
        boolean validSize = within(width) && within(height);

        if (!defaultSize && !validSize) {
            return "Resolusi layar: lebar dan tinggi diisi berpasangan, masing-masing " + MIN_SIZE + " sampai "
                    + MAX_SIZE + " — atau keduanya 0 untuk bawaan agent (1024x768).";
        }

        if (!DEPTHS.contains(depth)) {
            return "Kedalaman warna harus 0 (bawaan), 15, 16, 24, atau 32.";
        }

        return null;
    }

    private static boolean within(int size) {
        return size >= MIN_SIZE && size <= MAX_SIZE;
    }
}
