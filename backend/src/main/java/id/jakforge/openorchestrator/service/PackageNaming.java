package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;

import java.util.regex.Pattern;

/**
 * Aturan nama dan versi paket — SATU untuk paket dari Studio dan yang
 * diunggah manual, supaya keduanya bertemu di proses dan riwayat versi yang
 * sama.
 *
 * <p>Yang ditolak hanya yang memang rusak di tempat lain: "/" dan "\" di nama
 * memecah alamat {@code /api/packages/{name}/{version}/content} yang dipakai
 * robot, dan versi yang bukan angka bertitik tidak bisa diurutkan (versi
 * terbaru, "naikkan versinya"). Studio selalu mengirim nama proyek dan versi
 * x.y.z, jadi penerbitannya tidak terkena.
 */
final class PackageNaming {

    static final int MAX_NAME_LENGTH = 200;
    static final int MAX_VERSION_LENGTH = 64;

    /** errorCode: nama paket tidak sah. */
    static final String INVALID_NAME = "PACKAGE_INVALID_NAME";
    /** errorCode: versi paket tidak sah. */
    static final String INVALID_VERSION = "PACKAGE_INVALID_VERSION";

    /** 1.0, 1.0.3, 1.0.3.7, dengan akhiran pra-rilis (-beta.2) dan metadata build (+abc) bila ada. */
    private static final Pattern VERSION =
            Pattern.compile("\\d{1,9}(\\.\\d{1,9}){1,3}(-[0-9A-Za-z][0-9A-Za-z.-]*)?(\\+[0-9A-Za-z.-]+)?");

    private PackageNaming() {
    }

    static void validate(String name, String version) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("Nama paket wajib diisi.").withCode(INVALID_NAME);
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw ApiException.badRequest("Nama paket paling banyak " + MAX_NAME_LENGTH + " karakter.")
                    .withCode(INVALID_NAME);
        }
        if (name.chars().anyMatch(c -> c == '/' || c == '\\' || Character.isISOControl(c))
                || name.equals(".") || name.equals("..")) {
            throw ApiException.badRequest("Nama paket tidak boleh memuat '/', '\\', atau karakter kendali: '"
                    + name.replaceAll("\\p{Cntrl}", "?") + "'.").withCode(INVALID_NAME);
        }

        if (version == null || version.length() > MAX_VERSION_LENGTH || !VERSION.matcher(version).matches()) {
            throw ApiException.badRequest("Versi paket tidak sah: '" + (version == null ? "" : version)
                            + "'. Bentuknya angka bertitik, mis. 1.0.3 (boleh berakhiran pra-rilis, mis. 1.0.3-beta).")
                    .withCode(INVALID_VERSION);
        }
    }

    static boolean isValidVersion(String version) {
        return version != null && version.length() <= MAX_VERSION_LENGTH && VERSION.matcher(version).matches();
    }

    /**
     * Urutan versi seperti SemVer: bagian angka dibandingkan sebagai ANGKA
     * (1.0.10 sesudah 1.0.9), pra-rilis lebih rendah daripada rilisnya
     * (1.0.0-beta &lt; 1.0.0), metadata build diabaikan. Sama dengan
     * {@code bandingkanVersi} di dasbor.
     */
    static int compare(String a, String b) {
        String[] x = split(a);
        String[] y = split(b);

        int core = compareIdentifiers(x[0].split("\\."), y[0].split("\\."), true);
        if (core != 0) return core;

        if (x[1] == null || y[1] == null) return x[1] == null ? (y[1] == null ? 0 : 1) : -1;

        return compareIdentifiers(x[1].split("\\."), y[1].split("\\."), false);
    }

    /** [inti, pra-rilis atau null], tanpa metadata build. */
    private static String[] split(String version) {
        String v = version == null ? "" : version.trim();
        int plus = v.indexOf('+');
        if (plus >= 0) v = v.substring(0, plus);
        int dash = v.indexOf('-');
        return dash < 0 ? new String[]{v, null} : new String[]{v.substring(0, dash), v.substring(dash + 1)};
    }

    /** @param padWithZero bagian inti yang hilang dianggap 0 (1.0 = 1.0.0); pra-rilis yang lebih pendek lebih rendah */
    private static int compareIdentifiers(String[] a, String[] b, boolean padWithZero) {
        int n = Math.max(a.length, b.length);

        for (int i = 0; i < n; i++) {
            String p = i < a.length ? a[i] : null;
            String q = i < b.length ? b[i] : null;

            if (p == null || q == null) {
                if (!padWithZero) return p == null ? -1 : 1;
                p = p == null ? "0" : p;
                q = q == null ? "0" : q;
            }

            boolean pNumber = !p.isEmpty() && p.chars().allMatch(Character::isDigit);
            boolean qNumber = !q.isEmpty() && q.chars().allMatch(Character::isDigit);

            int result;
            if (pNumber && qNumber) result = compareDigits(p, q);
            else if (pNumber != qNumber) result = pNumber ? -1 : 1;
            else result = p.compareTo(q);

            if (result != 0) return result;
        }

        return 0;
    }

    /** Angka sepanjang apa pun, tanpa batas long: nol di depan dibuang, lalu panjangnya, lalu digitnya. */
    private static int compareDigits(String p, String q) {
        String a = p.replaceFirst("^0+(?=.)", "");
        String b = q.replaceFirst("^0+(?=.)", "");
        return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
    }
}
