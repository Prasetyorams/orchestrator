package id.jakforge.openorchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Masuk dengan akun Windows Active Directory (V19), lewat LDAPS.
 *
 * <p>Opsional: {@code enabled=false} (bawaan) berarti tidak ada yang dibaca
 * atau diperiksa, dan masuk dengan akun lokal berjalan seperti biasa. Kalau
 * diaktifkan, setelan yang salah membuat aplikasi GAGAL menyala dengan pesan
 * yang menyebut setelannya — lebih baik daripada menyala lalu menolak setiap
 * orang yang masuk dengan Windows AD.
 *
 * <p>Tidak ada nama domain, server, OU, atau grup yang ditanam di kode: semuanya
 * dari lingkungan (AD_* di application.yml).
 *
 * @param url              satu atau lebih ldaps://server:636 dipisah spasi (cadangan); HANYA ldaps
 * @param baseDn           akar domain, mis. DC=contoh,DC=local — juga penanda direktori asal identitas
 * @param userSearchBase   tempat mencari akun; kosong = baseDn
 * @param userSearchFilter filter pencarian akun, {0} = nama pengguna yang sudah di-escape
 * @param upnSuffix        akhiran UPN untuk bind langsung TANPA akun layanan (nama@akhiran)
 * @param bindDn           akun layanan hanya-baca, untuk mencari akun saat mendaftarkan pengguna; boleh kosong
 * @param bindPassword     sandi akun layanan — dari lingkungan/rahasia, tidak pernah di kode atau basis data
 * @param allowedGroups    DN grup yang boleh masuk, dipisah titik koma; kosong = tanpa syarat grup
 * @param trustStore       berkas PEM berisi sertifikat CA yang menerbitkan sertifikat domain controller;
 *                         kosong = trust store bawaan Java
 */
@ConfigurationProperties(prefix = "openorchestrator.ad")
public record AdProperties(boolean enabled, String label, String url, String baseDn, String userSearchBase,
                           String userSearchFilter, String upnSuffix, String bindDn, String bindPassword,
                           String allowedGroups, String trustStore, Duration connectTimeout, Duration readTimeout) {

    public static final String DEFAULT_FILTER = "(&(objectCategory=person)(objectClass=user)(sAMAccountName={0}))";

    public AdProperties {
        label = blankToNull(label) == null ? "Windows AD" : label.trim();
        url = blankToNull(url);
        baseDn = blankToNull(baseDn);
        userSearchBase = blankToNull(userSearchBase);
        userSearchFilter = blankToNull(userSearchFilter) == null ? DEFAULT_FILTER : userSearchFilter.trim();
        upnSuffix = blankToNull(upnSuffix);
        bindDn = blankToNull(bindDn);
        bindPassword = bindPassword == null || bindPassword.isEmpty() ? null : bindPassword;
        allowedGroups = blankToNull(allowedGroups);
        trustStore = blankToNull(trustStore);
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }

    /** Tanpa AD sama sekali — untuk uji dan untuk aplikasi yang tidak memakainya. */
    public static AdProperties disabled() {
        return new AdProperties(false, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public String searchBase() {
        return userSearchBase != null ? userSearchBase : baseDn;
    }

    public boolean hasServiceAccount() {
        return bindDn != null;
    }

    public List<String> allowedGroupDns() {
        if (allowedGroups == null) return List.of();
        return Arrays.stream(allowedGroups.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
