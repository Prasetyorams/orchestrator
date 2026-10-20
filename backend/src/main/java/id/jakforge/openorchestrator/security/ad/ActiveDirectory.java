package id.jakforge.openorchestrator.security.ad;

import id.jakforge.openorchestrator.config.AdProperties;
import id.jakforge.openorchestrator.security.ad.AdException.Failure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.naming.AuthenticationException;
import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.PartialResultException;
import javax.naming.ServiceUnavailableException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Klien Windows Active Directory lewat LDAPS (V19), memakai JNDI bawaan JDK —
 * tanpa pustaka tambahan.
 *
 * <p>Aturan keamanannya:
 * <ul>
 *   <li>Hanya {@code ldaps://}. Sertifikat domain controller DIPERIKSA (trust
 *       store AD_TRUST_STORE, atau bawaan Java), begitu juga namanya
 *       (endpoint identification JNDI) — tidak ada opsi untuk mematikannya.</li>
 *   <li>Sandi kosong ditolak SEBELUM menyentuh jaringan: simple bind dengan
 *       sandi kosong adalah "unauthenticated bind", yang di sebagian server
 *       dijawab berhasil.</li>
 *   <li>Nilai dari pengguna masuk ke filter LDAP hanya lewat argumen filter
 *       JNDI (di-escape menurut RFC 4515), tidak pernah disambung ke teks
 *       filter.</li>
 *   <li>Tanpa akun layanan: bind langsung sebagai {@code nama@AD_UPN_SUFFIX},
 *       lalu membaca entri sendiri dengan sambungan itu. Akun layanan
 *       (AD_BIND_DN, hanya-baca) hanya dibutuhkan untuk MENCARI akun saat
 *       admin mendaftarkan pengguna.</li>
 * </ul>
 */
@Slf4j
@Component
public class ActiveDirectory {

    static final String[] ATTRIBUTES = {
            "objectGUID", "sAMAccountName", "userPrincipalName", "displayName", "mail", "distinguishedName",
            "userAccountControl"};

    /** userAccountControl: ACCOUNTDISABLE. */
    static final int UAC_DISABLED = 0x2;
    /** Matching rule AD untuk keanggotaan bersarang (LDAP_MATCHING_RULE_IN_CHAIN). */
    static final String IN_CHAIN_MEMBER_FILTER = "(member:1.2.840.113556.1.4.1941:={0})";
    /** Karakter yang tidak sah di sAMAccountName, plus kendali. */
    private static final Pattern INVALID_USERNAME = Pattern.compile("[\"/\\\\\\[\\]:;|=,+*?<>@\\p{Cntrl}]");
    private static final int MAX_USERNAME_LENGTH = 64;
    /** "... AcceptSecurityContext error, data 52e, v4563" — kode sub-galat AD. */
    private static final Pattern AD_SUBCODE = Pattern.compile("data ([0-9a-fA-F]{3,8})");

    private final AdProperties properties;
    private final SSLSocketFactory socketFactory;

    public ActiveDirectory(AdProperties properties) {
        this.properties = properties;
        this.socketFactory = properties.enabled() ? validateAndPrepare(properties) : null;

        if (properties.enabled()) {
            log.info("Masuk Windows AD aktif: {} (cari akun: {}, syarat grup: {}).", properties.url(),
                    properties.hasServiceAccount() ? "akun layanan" : "tidak — tertaut saat masuk pertama",
                    properties.allowedGroupDns().isEmpty() ? "tidak ada" : properties.allowedGroupDns().size() + " grup");
        }
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** Bisa mencari akun tanpa sandi pemiliknya — butuh akun layanan. */
    public boolean canLookUp() {
        return properties.enabled() && properties.hasServiceAccount();
    }

    public String label() {
        return properties.label();
    }

    /** Penanda direktori asal identitas yang disimpan bersama objectGUID. */
    public String directory() {
        return properties.baseDn();
    }

    // -----------------------------------------------------------------
    // Masuk
    // -----------------------------------------------------------------

    /**
     * Periksa nama dan sandi ke AD. Berhasil = sandi benar DAN akun aktif;
     * keanggotaan grup dilaporkan di {@link AdAccount#allowedByGroups()} untuk
     * diputuskan pemanggil SESUDAH memeriksa pendaftaran.
     *
     * @throws AdException kegagalan apa pun — lihat {@link Failure}
     */
    public AdAccount authenticate(String username, String password) {
        requireEnabled();
        String sam = normalizeUsername(username);
        if (sam == null || password == null || password.isEmpty()) {
            throw new AdException(Failure.INVALID_CREDENTIALS, "nama kosong/tidak sah atau sandi kosong; tidak dikirim ke AD");
        }

        if (properties.hasServiceAccount()) {
            try (Connection service = connect(properties.bindDn(), properties.bindPassword(), true)) {
                AdAccount found = findAccount(service, sam)
                        .orElseThrow(() -> new AdException(Failure.INVALID_CREDENTIALS, "akun '" + sam + "' tidak ada di direktori"));

                try (Connection user = connect(found.distinguishedName(), password, false)) {
                    // Sandinya benar. Grup dibaca dengan akun layanan, yang memang boleh membacanya.
                    return withGroups(service, requireEnabledAccount(found));
                }
            }
        }

        try (Connection user = connect(sam + "@" + properties.upnSuffix(), password, false)) {
            AdAccount found = findAccount(user, sam).orElseThrow(() -> new AdException(Failure.DIRECTORY_ERROR,
                    "bind sebagai '" + sam + "' berhasil tetapi akunnya tidak ditemukan di " + properties.searchBase()
                            + " — periksa AD_USER_SEARCH_BASE dan AD_USER_SEARCH_FILTER"));
            return withGroups(user, requireEnabledAccount(found));
        }
    }

    /** Cari akun tanpa sandinya (admin mendaftarkan pengguna). Butuh akun layanan. */
    public Optional<AdAccount> lookUp(String username) {
        requireEnabled();
        if (!properties.hasServiceAccount()) {
            throw new AdException(Failure.LOOKUP_UNAVAILABLE, "AD_BIND_DN tidak diisi");
        }
        String sam = normalizeUsername(username);
        if (sam == null) return Optional.empty();

        try (Connection service = connect(properties.bindDn(), properties.bindPassword(), true)) {
            Optional<AdAccount> found = findAccount(service, sam);
            return found.isEmpty() ? found : Optional.of(withGroups(service, found.get()));
        }
    }

    // -----------------------------------------------------------------
    // Alat yang bisa diuji tanpa direktori
    // -----------------------------------------------------------------

    /**
     * "nama", "DOMAIN\nama", atau "nama@domain" → sAMAccountName "nama"; null
     * kalau kosong atau memuat karakter yang tidak sah di sAMAccountName.
     * Akhiran domain yang diketik pengguna tidak dipakai: yang diperiksa selalu
     * domain yang disetel server.
     */
    public static String normalizeUsername(String input) {
        if (input == null) return null;
        String value = input.trim();
        int backslash = value.lastIndexOf('\\');
        if (backslash >= 0) value = value.substring(backslash + 1);
        int at = value.indexOf('@');
        if (at >= 0) value = value.substring(0, at);
        value = value.trim();
        if (value.isEmpty() || value.length() > MAX_USERNAME_LENGTH || INVALID_USERNAME.matcher(value).find()) return null;
        return value;
    }

    /**
     * objectGUID AD (16 bita, tiga bagian pertama little-endian) → UUID dalam
     * bentuk yang ditampilkan Windows ({xxxxxxxx-xxxx-...}).
     */
    static UUID guidFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 16) throw new IllegalArgumentException("objectGUID harus 16 bita");
        ByteBuffer le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long data1 = le.getInt(0) & 0xFFFFFFFFL;
        long data2 = le.getShort(4) & 0xFFFFL;
        long data3 = le.getShort(6) & 0xFFFFL;
        long high = (data1 << 32) | (data2 << 16) | data3;
        long low = ByteBuffer.wrap(bytes, 8, 8).order(ByteOrder.BIG_ENDIAN).getLong();
        return new UUID(high, low);
    }

    static byte[] guidToBytes(UUID guid) {
        ByteBuffer out = ByteBuffer.allocate(16);
        long high = guid.getMostSignificantBits();
        out.order(ByteOrder.LITTLE_ENDIAN).putInt((int) (high >>> 32)).putShort((short) (high >>> 16)).putShort((short) high);
        out.order(ByteOrder.BIG_ENDIAN).putLong(guid.getLeastSignificantBits());
        return out.array();
    }

    /** Kode sub-galat AD dari pesan bind yang ditolak → jenis kegagalannya. */
    static Failure failureOf(AuthenticationException e) {
        Matcher m = AD_SUBCODE.matcher(String.valueOf(e.getMessage()));
        if (!m.find()) return Failure.INVALID_CREDENTIALS;
        return switch (m.group(1).toLowerCase(Locale.ROOT)) {
            case "533" -> Failure.ACCOUNT_DISABLED;
            case "775" -> Failure.ACCOUNT_LOCKED;
            case "701" -> Failure.ACCOUNT_EXPIRED;
            case "532" -> Failure.PASSWORD_EXPIRED;
            case "773" -> Failure.PASSWORD_MUST_CHANGE;
            case "530", "531" -> Failure.LOGON_RESTRICTED;
            default -> Failure.INVALID_CREDENTIALS; // 52e, 525, dan yang tidak dikenal
        };
    }

    // -----------------------------------------------------------------

    private void requireEnabled() {
        if (!properties.enabled()) throw new AdException(Failure.DIRECTORY_ERROR, "masuk Windows AD tidak diaktifkan");
    }

    private AdAccount requireEnabledAccount(AdAccount account) {
        if (!account.enabled()) {
            throw new AdException(Failure.ACCOUNT_DISABLED, "akun '" + account.samAccountName() + "' dinonaktifkan di AD");
        }
        return account;
    }

    private Optional<AdAccount> findAccount(Connection connection, String sam) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(ATTRIBUTES);
        controls.setCountLimit(2);
        controls.setTimeLimit((int) properties.readTimeout().toMillis());

        List<AdAccount> found = new ArrayList<>();
        try {
            NamingEnumeration<SearchResult> results = connection.context()
                    .search(properties.searchBase(), properties.userSearchFilter(), new Object[]{sam}, controls);
            try {
                while (results.hasMore()) found.add(toAccount(results.next()));
            } catch (PartialResultException ignored) {
                // Rujukan ke domain lain (referral=ignore): yang di domain ini sudah terbaca.
            } finally {
                results.close();
            }
        } catch (javax.naming.SizeLimitExceededException e) {
            throw new AdException(Failure.DIRECTORY_ERROR, "lebih dari satu akun cocok dengan '" + sam
                    + "' — persempit AD_USER_SEARCH_FILTER");
        } catch (NamingException e) {
            throw translate(e, "mencari akun '" + sam + "'");
        }

        if (found.size() > 1) {
            throw new AdException(Failure.DIRECTORY_ERROR, "lebih dari satu akun cocok dengan '" + sam
                    + "' — persempit AD_USER_SEARCH_FILTER");
        }
        return found.stream().findFirst();
    }

    /** Anggota (langsung atau lewat grup bersarang) salah satu grup yang diizinkan? */
    private AdAccount withGroups(Connection connection, AdAccount account) {
        List<String> groups = properties.allowedGroupDns();
        if (groups.isEmpty()) return account;

        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.OBJECT_SCOPE);
        controls.setReturningAttributes(new String[]{"distinguishedName"});
        controls.setTimeLimit((int) properties.readTimeout().toMillis());

        for (String group : groups) {
            try {
                NamingEnumeration<SearchResult> r = connection.context()
                        .search(group, IN_CHAIN_MEMBER_FILTER, new Object[]{account.distinguishedName()}, controls);
                try {
                    if (r.hasMore()) return withAllowed(account, true);
                } finally {
                    r.close();
                }
            } catch (NameNotFoundException e) {
                log.warn("Grup Windows AD yang diizinkan tidak ada di direktori: {}", group);
            } catch (NamingException e) {
                throw translate(e, "memeriksa grup " + group);
            }
        }
        return withAllowed(account, false);
    }

    private static AdAccount withAllowed(AdAccount a, boolean allowed) {
        return new AdAccount(a.objectGuid(), a.samAccountName(), a.userPrincipalName(), a.displayName(), a.email(),
                a.distinguishedName(), a.enabled(), allowed);
    }

    private static AdAccount toAccount(SearchResult result) throws NamingException {
        Attributes attrs = result.getAttributes();
        Object guid = value(attrs, "objectGUID");
        if (!(guid instanceof byte[] bytes)) throw new NamingException("objectGUID tidak terbaca sebagai biner");

        String dn = text(attrs, "distinguishedName");
        String uac = text(attrs, "userAccountControl");
        boolean enabled = uac == null || (Integer.parseInt(uac.trim()) & UAC_DISABLED) == 0;

        return new AdAccount(guidFromBytes(bytes), text(attrs, "sAMAccountName"), text(attrs, "userPrincipalName"),
                text(attrs, "displayName"), text(attrs, "mail"), dn != null ? dn : result.getNameInNamespace(), enabled,
                true);
    }

    private static Object value(Attributes attrs, String name) throws NamingException {
        Attribute a = attrs.get(name);
        return a == null ? null : a.get();
    }

    private static String text(Attributes attrs, String name) throws NamingException {
        Object v = value(attrs, name);
        return v == null ? null : v.toString();
    }

    /** DirContext yang bisa dipakai di try-with-resources. */
    private record Connection(DirContext context) implements AutoCloseable {
        @Override
        public void close() {
            try {
                context.close();
            } catch (NamingException ignored) {
                // Sudah selesai dipakai.
            }
        }
    }

    /**
     * Sambungan LDAPS yang sudah bind. Kegagalan bind sebagai akun layanan
     * adalah masalah SETELAN (DIRECTORY_ERROR), bukan sandi pengguna.
     */
    private Connection connect(String principal, String password, boolean serviceAccount) {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, properties.url());
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, password);
        env.put(Context.REFERRAL, "ignore");
        env.put("java.naming.ldap.version", "3");
        env.put("java.naming.ldap.attributes.binary", "objectGUID");
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(properties.connectTimeout().toMillis()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(properties.readTimeout().toMillis()));
        env.put("com.sun.jndi.ldap.connect.pool", "false");
        if (socketFactory != null) env.put("java.naming.ldap.factory.socket", AdSocketFactory.class.getName());

        try {
            DirContext context = socketFactory == null
                    ? new InitialLdapContext(env, null)
                    : AdSocketFactory.during(socketFactory, () -> new InitialLdapContext(env, null));
            return new Connection(context);
        } catch (AuthenticationException e) {
            if (serviceAccount) {
                throw new AdException(Failure.DIRECTORY_ERROR, "akun layanan AD_BIND_DN ditolak AD: " + e.getMessage(), e);
            }
            throw new AdException(failureOf(e), "bind ditolak: " + e.getMessage(), e);
        } catch (NamingException e) {
            throw translate(e, "menyambung ke " + properties.url());
        }
    }

    private static AdException translate(NamingException e, String what) {
        if (hasCause(e, SSLHandshakeException.class) || hasCause(e, CertificateException.class)) {
            return new AdException(Failure.TLS, what + ": TLS ditolak (sertifikat tidak dipercaya atau nama server tidak cocok) — "
                    + rootMessage(e), e);
        }
        if (e instanceof CommunicationException || e instanceof ServiceUnavailableException
                || hasCause(e, SocketTimeoutException.class) || hasCause(e, java.net.ConnectException.class)
                || String.valueOf(e.getMessage()).contains("timed out")) {
            return new AdException(Failure.UNREACHABLE, what + ": " + rootMessage(e), e);
        }
        return new AdException(Failure.DIRECTORY_ERROR, what + ": " + rootMessage(e), e);
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause() != t ? t.getCause() : null) {
            if (type.isInstance(t)) return true;
            if (t instanceof NamingException ne && ne.getRootCause() != null && type.isInstance(ne.getRootCause())) return true;
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (true) {
            Throwable next = t instanceof NamingException ne && ne.getRootCause() != null ? ne.getRootCause() : t.getCause();
            if (next == null || next == t) return String.valueOf(t.getMessage());
            t = next;
        }
    }

    /**
     * Periksa setelan saat aplikasi mulai — hanya kalau AD diaktifkan — dan
     * siapkan TLS dengan trust store-nya. Pesannya menyebut nama variabel
     * lingkungannya, supaya yang memasang tahu apa yang harus diperbaiki.
     */
    static SSLSocketFactory validateAndPrepare(AdProperties p) {
        List<String> problems = new ArrayList<>();

        if (p.url() == null) {
            problems.add("AD_LDAP_URL wajib diisi, mis. ldaps://dc01.contoh.local:636");
        } else {
            for (String url : p.url().split("\\s+")) {
                if (!url.toLowerCase(Locale.ROOT).startsWith("ldaps://")) {
                    problems.add("AD_LDAP_URL harus ldaps:// (TLS, port 636): '" + url
                            + "'. LDAP tanpa enkripsi tidak diizinkan, dan StartTLS belum didukung.");
                }
            }
        }
        if (p.baseDn() == null) problems.add("AD_BASE_DN wajib diisi, mis. DC=contoh,DC=local");
        if (!p.userSearchFilter().contains("{0}")) problems.add("AD_USER_SEARCH_FILTER harus memuat {0} (nama pengguna)");
        if (p.upnSuffix() == null && p.bindDn() == null) {
            problems.add("isi AD_UPN_SUFFIX (masuk tanpa akun layanan) atau AD_BIND_DN + AD_BIND_PASSWORD (akun layanan hanya-baca)");
        }
        if (p.bindDn() != null && p.bindPassword() == null) problems.add("AD_BIND_PASSWORD wajib diisi kalau AD_BIND_DN diisi");
        if (p.connectTimeout().isNegative() || p.connectTimeout().isZero()) problems.add("AD_CONNECT_TIMEOUT_MS harus lebih dari 0");
        if (p.readTimeout().isNegative() || p.readTimeout().isZero()) problems.add("AD_READ_TIMEOUT_MS harus lebih dari 0");

        SSLSocketFactory factory = null;
        if (p.trustStore() != null) {
            try {
                factory = trustStoreFactory(Path.of(p.trustStore()));
            } catch (Exception e) {
                problems.add("AD_TRUST_STORE '" + p.trustStore() + "' tidak bisa dibaca sebagai sertifikat PEM: " + e.getMessage());
            }
        } else {
            try {
                factory = SSLContext.getDefault().getSocketFactory();
            } catch (Exception e) {
                problems.add("TLS bawaan Java tidak tersedia: " + e.getMessage());
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Setelan masuk Windows AD (AD_AUTH_ENABLED=true) tidak lengkap: "
                    + String.join("; ", problems) + ".");
        }
        return factory;
    }

    /** Trust store dari berkas PEM berisi satu atau lebih sertifikat CA. */
    static SSLSocketFactory trustStoreFactory(Path pem) throws Exception {
        Collection<? extends Certificate> certificates;
        try (InputStream in = Files.newInputStream(pem)) {
            certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
        }
        if (certificates.isEmpty()) throw new CertificateException("tidak ada sertifikat di berkasnya");

        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        int i = 0;
        for (Certificate c : certificates) store.setCertificateEntry("ad-ca-" + i++, c);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context.getSocketFactory();
    }
}
