package id.jakforge.forgehub.security;

/**
 * Apakah peran seseorang mengizinkan sebuah tindakan, mis. "folders.update".
 *
 * <p>Antarmuka, bukan langsung {@link Izin}: layanan yang memakainya bisa
 * diuji dengan aturan sederhana tanpa basis data.
 */
@FunctionalInterface
public interface PemeriksaIzin {

    boolean boleh(ForgeHubPrincipal p, String izin);
}
