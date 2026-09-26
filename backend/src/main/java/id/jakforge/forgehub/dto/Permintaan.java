package id.jakforge.forgehub.dto;

import id.jakforge.forgehub.common.Badan;

import java.util.List;
import java.util.Map;

/**
 * Bentuk permintaan yang dipakai lebih dari satu endpoint.
 *
 * <p>Dikumpulkan di satu berkas karena semuanya kecil dan sifatnya sama:
 * membaca badan permintaan yang longgar menjadi record bertipe. Lima belas
 * berkas berisi satu record tiga medan tidak menolong siapa pun yang mencari.
 *
 * <p>Yang dipisah ke berkasnya sendiri hanyalah yang membawa aturan — JobRequest
 * dan TriggerRequest — karena di sana bentuknya memang perlu dijelaskan.
 */
public final class Permintaan {

    private Permintaan() {
    }

    /** Nama dan keterangan: lingkungan, gudang berkas, dan antrean sederhana. */
    public record Bernama(String name, String description) {

        public static Bernama dari(Map<String, Object> body) {
            return new Bernama(Badan.nama(body, "name"), Badan.teks(body, "description"));
        }
    }

    public record Mesin(String name, String type, String licenseKey, String description) {

        public static Mesin dari(Map<String, Object> body) {
            return new Mesin(
                    Badan.nama(body, "name"),
                    Badan.teks(body, "type", "Standard"),
                    Badan.teks(body, "licenseKey"),
                    Badan.teks(body, "description"));
        }
    }

    public record Robot(String name, String machineName, String username,
                        String type, String environment, String description) {

        public static Robot dari(Map<String, Object> body) {
            return new Robot(
                    Badan.nama(body, "name"),
                    Badan.teks(body, "machineName"),
                    Badan.teks(body, "username"),
                    Badan.teks(body, "type", "Unattended"),
                    Badan.teks(body, "environment", "Production"),
                    Badan.teks(body, "description"));
        }
    }

    /**
     * Denyut dari robot.
     *
     * <p>machineName boleh null, dan itu berarti "tidak disebut kali ini" —
     * bukan "kosongkan". Repository memakai COALESCE untuk menjaga bedanya.
     */
    public record Denyut(String status, double cpuPercent, double memoryMb, String machineName) {

        public static Denyut dari(Map<String, Object> body) {
            return new Denyut(
                    Badan.teks(body, "status", "AVAILABLE").toUpperCase(java.util.Locale.ROOT),
                    Badan.angka(body, "cpuPercent", 0),
                    Badan.angka(body, "memoryMb", 0),
                    Badan.teks(body, "machineName"));
        }
    }

    public record Proses(String name, String packageName, String packageVersion,
                         String environment, String description) {

        public static Proses dari(Map<String, Object> body) {
            return new Proses(
                    Badan.nama(body, "name"),
                    Badan.teks(body, "packageName"),
                    Badan.teks(body, "packageVersion"),
                    Badan.teks(body, "environment"),
                    Badan.teks(body, "description"));
        }
    }

    public record Paket(String name, String version, String description, String entryPoint,
                        String environment, String contentBase64) {

        public static Paket dari(Map<String, Object> body) {
            return new Paket(
                    Badan.nama(body, "name"),
                    Badan.teks(body, "version", "1.0.0"),
                    Badan.teks(body, "description"),
                    Badan.teks(body, "entryPoint"),
                    Badan.teks(body, "environment", "Production"),
                    Badan.teks(body, "contentBase64"));
        }
    }

    public record Antrean(String name, String description, int maxRetries, boolean acceptDuplicates) {

        public static Antrean dari(Map<String, Object> body) {
            return new Antrean(
                    Badan.nama(body, "name"),
                    Badan.teks(body, "description"),
                    Badan.bulat(body, "maxRetries", 3),
                    Badan.benar(body, "acceptDuplicates", false));
        }
    }

    public record ButirAntrean(String reference, String priority, String content) {

        public static ButirAntrean dari(Map<String, Object> body) {
            return new ButirAntrean(
                    Badan.teks(body, "reference"),
                    Badan.teks(body, "priority", "Normal"),
                    Badan.teks(body, "content"));
        }
    }

    public record HasilButir(String status, String output, String exception) {

        public static HasilButir dari(Map<String, Object> body) {
            return new HasilButir(
                    Badan.teks(body, "status", ""),
                    Badan.teks(body, "output"),
                    Badan.teks(body, "exception"));
        }
    }

    /** {@code username} hanya berarti untuk aset bertipe Credential; tipe lain mengabaikannya. */
    public record Aset(String name, String type, String username, String value, String description,
                       String scope) {

        public static Aset dari(Map<String, Object> body) {
            return new Aset(
                    Badan.nama(body, "name"),
                    Badan.teks(body, "type", "Text"),
                    Badan.teks(body, "username"),
                    Badan.teks(body, "value"),
                    Badan.teks(body, "description"),
                    Badan.teks(body, "scope", "Global"));
        }
    }

    public record Kredensial(String name, String username, String password, String description) {

        public static Kredensial dari(Map<String, Object> body) {
            return new Kredensial(
                    Badan.nama(body, "name"),
                    Badan.teks(body, "username"),
                    Badan.teks(body, "password"),
                    Badan.teks(body, "description"));
        }
    }

    public record Berkas(String fileName, String contentBase64, String contentType) {

        public static Berkas dari(Map<String, Object> body) {
            return new Berkas(
                    Badan.nama(body, "fileName"),
                    Badan.teks(body, "contentBase64", ""),
                    Badan.teks(body, "contentType", "application/octet-stream"));
        }
    }

    /**
     * Membuat atau menyunting folder.
     *
     * <p>{@code gantiInduk} membedakan "parentId tidak disebut" dari "parentId
     * kosong": yang pertama berarti biarkan foldernya di tempatnya, yang kedua
     * berarti pindahkan ke akar. Badan yang longgar tidak bisa membedakan
     * keduanya lewat nilainya saja — keduanya terbaca null.
     */
    public record Folder(String name, String description, String parentId, boolean gantiInduk) {

        public static Folder dari(Map<String, Object> body) {
            return new Folder(
                    Badan.nama(body, "name"),
                    Badan.teks(body, "description"),
                    Badan.nama(body, "parentId"),
                    body != null && body.containsKey("parentId"));
        }
    }

    public record Masuk(String username, String password) {

        public static Masuk dari(Map<String, Object> body) {
            return new Masuk(Badan.nama(body, "username"), Badan.teks(body, "password"));
        }
    }

    public record GantiSandi(String currentPassword, String newPassword) {

        public static GantiSandi dari(Map<String, Object> body) {
            return new GantiSandi(
                    Badan.teks(body, "currentPassword"),
                    Badan.teks(body, "newPassword"));
        }
    }

    /**
     * Profil milik pengguna yang sedang masuk.
     *
     * <p>Hanya nama tampilan dan surel. Nama pengguna dan peran sengaja tidak
     * ada di sini: nama pengguna dipakai untuk masuk — termasuk oleh robot
     * lewat jakrunner.json — dan peran yang bisa diubah pemiliknya sendiri
     * bukan lagi pembatasan.
     */
    public record Profil(String displayName, String email) {

        public static Profil dari(Map<String, Object> body) {
            return new Profil(Badan.nama(body, "displayName"), Badan.nama(body, "email"));
        }
    }

    /** Peran dari layar Peran. permissions null berarti tidak dikirim. */
    public record Peran(String name, String description, List<String> permissions) {

        public static Peran dari(Map<String, Object> body) {
            return new Peran(
                    Badan.nama(body, "name"),
                    Badan.teks(body, "description"),
                    Badan.daftar(body, "permissions"));
        }
    }

    public record Pengguna(String username, String password, String displayName,
                           String email, String role, boolean isActive) {

        public static Pengguna dari(Map<String, Object> body) {
            return new Pengguna(
                    Badan.nama(body, "username"),
                    Badan.teks(body, "password"),
                    Badan.teks(body, "displayName"),
                    Badan.teks(body, "email"),
                    Badan.teks(body, "role"),
                    Badan.benar(body, "isActive", true));
        }
    }
}
