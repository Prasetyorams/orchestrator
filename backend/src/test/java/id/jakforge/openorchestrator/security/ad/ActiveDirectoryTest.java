package id.jakforge.openorchestrator.security.ad;

import id.jakforge.openorchestrator.config.AdProperties;
import id.jakforge.openorchestrator.security.ad.AdException.Failure;
import id.jakforge.openorchestrator.support.FakeActiveDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.naming.AuthenticationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import static id.jakforge.openorchestrator.support.FakeActiveDirectory.GROUPS;
import static id.jakforge.openorchestrator.support.FakeActiveDirectory.SERVICE_DN;
import static id.jakforge.openorchestrator.support.FakeActiveDirectory.SERVICE_PASSWORD;
import static id.jakforge.openorchestrator.support.FakeActiveDirectory.SUFFIX;
import static id.jakforge.openorchestrator.support.FakeActiveDirectory.UPN_SUFFIX;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Klien Windows AD melawan server LDAPS tiruan: bind, identitas, grup, TLS, dan setelan. */
class ActiveDirectoryTest {

    static FakeActiveDirectory ad;
    static UUID dinaGuid = UUID.fromString("2d5f9f23-9b56-4c2b-8a1e-6f1b2d5d3b8a");

    @BeforeAll
    static void start() throws Exception {
        ad = new FakeActiveDirectory();
        ad.addUser("dina", "Sandi-Dina-1", "Dina Aulia", dinaGuid, "CN=TimRPA," + GROUPS);
        ad.addUser("budi", "Sandi-Budi-1", "Budi Santoso", "CN=Keuangan," + GROUPS);
        ad.addUser("nonaktif", "Sandi-Non-1", "Akun Nonaktif");
        ad.disable("nonaktif");
        ad.addUser("terkunci", "Sandi-Kunci-1", "Akun Terkunci");
        ad.lock("terkunci");
    }

    @AfterAll
    static void stop() throws Exception {
        ad.close();
    }

    static AdProperties props(String url, String upnSuffix, String bindDn, String bindPassword, String groups, Path trust) {
        return new AdProperties(true, null, url, SUFFIX, null, null, upnSuffix, bindDn, bindPassword, groups,
                trust == null ? null : trust.toString(), Duration.ofSeconds(3), Duration.ofSeconds(5));
    }

    /** Tanpa akun layanan: bind langsung sebagai nama@uji.local. */
    static ActiveDirectory direct() {
        return new ActiveDirectory(props(ad.url(), UPN_SUFFIX, null, null, null, ad.trustPem()));
    }

    static ActiveDirectory withService(String groups) {
        return new ActiveDirectory(props(ad.url(), null, SERVICE_DN, SERVICE_PASSWORD, groups, ad.trustPem()));
    }

    static Failure failure(Executable action) {
        return assertThrows(AdException.class, action).failure();
    }

    // -------------------------------------------------------------------- masuk

    @Test
    @DisplayName("tanpa akun layanan: bind nama@akhiran, identitas dibaca — GUID, nama, surel, DN")
    void directBind() {
        AdAccount a = direct().authenticate("dina", "Sandi-Dina-1");

        assertEquals(dinaGuid, a.objectGuid());
        assertEquals("dina", a.samAccountName());
        assertEquals("Dina Aulia", a.displayName());
        assertEquals("dina@contoh.test", a.email());
        assertEquals("CN=Dina Aulia,OU=Users," + SUFFIX, a.distinguishedName());
        assertTrue(a.enabled());
        assertTrue(a.allowedByGroups(), "tanpa syarat grup");
        assertTrue(ad.binds.contains("dina@" + UPN_SUFFIX + "|berisi"));
    }

    @Test
    @DisplayName("DOMAIN\\nama dan nama@domain lain: yang diperiksa tetap domain yang disetel server")
    void usernameForms() {
        assertEquals(dinaGuid, direct().authenticate("UJI\\dina", "Sandi-Dina-1").objectGuid());
        assertEquals(dinaGuid, direct().authenticate(" dina@domain-lain.test ", "Sandi-Dina-1").objectGuid());
    }

    @Test
    @DisplayName("sandi salah, akun dinonaktifkan (533), terkunci (775): jenis kegagalannya dikenali")
    void credentialFailures() {
        assertEquals(Failure.INVALID_CREDENTIALS, failure(() -> direct().authenticate("dina", "salah")));
        assertEquals(Failure.INVALID_CREDENTIALS, failure(() -> direct().authenticate("tidakada", "apa-saja")));
        assertEquals(Failure.ACCOUNT_DISABLED, failure(() -> direct().authenticate("nonaktif", "Sandi-Non-1")));
        assertEquals(Failure.ACCOUNT_LOCKED, failure(() -> direct().authenticate("terkunci", "Sandi-Kunci-1")));
    }

    @Test
    @DisplayName("sandi kosong ditolak TANPA dikirim (server ini menerima unauthenticated bind)")
    void emptyPasswordNeverSent() {
        int before = ad.binds.size();
        assertEquals(Failure.INVALID_CREDENTIALS, failure(() -> direct().authenticate("dina", "")));
        assertEquals(Failure.INVALID_CREDENTIALS, failure(() -> direct().authenticate("dina", null)));
        assertEquals(Failure.INVALID_CREDENTIALS, failure(() -> withService(null).authenticate("dina", "")));
        assertEquals(before, ad.binds.size(), "tidak ada bind yang sampai ke server");
    }

    @Test
    @DisplayName("nama dengan karakter filter/DN ditolak; yang lolos di-escape, tidak bisa membelokkan filter")
    void injection() {
        int before = ad.binds.size();
        for (String evil : new String[]{"*", "dina)(sAMAccountName=*", "a,b", "x=y", "", " ", "a/b", "a\u0000b"}) {
            assertEquals(Failure.INVALID_CREDENTIALS, failure(() -> withService(null).authenticate(evil, "Sandi-Dina-1")), evil);
        }
        assertEquals(before, ad.binds.size());

        // "(" sah di sAMAccountName, jadi sampai ke filter — sebagai \28, bukan pembuka filter baru.
        assertEquals(Failure.INVALID_CREDENTIALS, failure(() -> withService(null).authenticate("dina(", "Sandi-Dina-1")));
        assertNull(ActiveDirectory.normalizeUsername("budi*"));
    }

    @Test
    @DisplayName("dengan akun layanan: cari akun lalu bind sebagai DN-nya; lookUp tanpa sandi pemiliknya")
    void serviceAccount() {
        ActiveDirectory d = withService(null);
        assertEquals(dinaGuid, d.authenticate("dina", "Sandi-Dina-1").objectGuid());
        assertTrue(ad.binds.contains("CN=Dina Aulia,OU=Users," + SUFFIX + "|berisi"));
        assertEquals(Failure.INVALID_CREDENTIALS, failure(() -> d.authenticate("dina", "salah")));
        assertEquals(Failure.INVALID_CREDENTIALS, failure(() -> d.authenticate("tidakada", "x")));

        assertEquals("Budi Santoso", d.lookUp("budi").orElseThrow().displayName());
        assertTrue(d.lookUp("tidakada").isEmpty());
        assertFalse(d.lookUp("nonaktif").orElseThrow().enabled(), "akun nonaktif terlihat nonaktif");
        assertTrue(d.canLookUp());
        assertFalse(direct().canLookUp());
        assertEquals(Failure.LOOKUP_UNAVAILABLE, failure(() -> direct().lookUp("budi")));
    }

    @Test
    @DisplayName("akun layanan yang sandinya salah: kesalahan SETELAN, bukan sandi pengguna")
    void wrongServicePassword() {
        ActiveDirectory d = new ActiveDirectory(props(ad.url(), null, SERVICE_DN, "salah", null, ad.trustPem()));
        assertEquals(Failure.DIRECTORY_ERROR, failure(() -> d.authenticate("dina", "Sandi-Dina-1")));
    }

    @Test
    @DisplayName("grup yang diizinkan: anggota lewat grup bersarang lolos, anggota grup lain tidak")
    void groups() {
        String allowed = "CN=OpenOrchestratorUsers," + GROUPS + "; CN=TidakAda," + GROUPS;
        assertTrue(withService(allowed).authenticate("dina", "Sandi-Dina-1").allowedByGroups());
        assertFalse(withService(allowed).authenticate("budi", "Sandi-Budi-1").allowedByGroups());

        ActiveDirectory directWithGroups = new ActiveDirectory(props(ad.url(), UPN_SUFFIX, null, null, allowed, ad.trustPem()));
        assertTrue(directWithGroups.authenticate("dina", "Sandi-Dina-1").allowedByGroups());
        assertFalse(directWithGroups.authenticate("budi", "Sandi-Budi-1").allowedByGroups());
    }

    // -------------------------------------------------------------------- TLS dan jaringan

    @Test
    @DisplayName("TLS diperiksa: tanpa trust store yang memuat CA-nya, atau CA lain → ditolak")
    void untrustedCertificate() throws Exception {
        ActiveDirectory javaDefault = new ActiveDirectory(props(ad.url(), UPN_SUFFIX, null, null, null, null));
        assertEquals(Failure.TLS, failure(() -> javaDefault.authenticate("dina", "Sandi-Dina-1")));

        try (FakeActiveDirectory other = new FakeActiveDirectory()) {
            ActiveDirectory wrongCa = new ActiveDirectory(props(ad.url(), UPN_SUFFIX, null, null, null, other.trustPem()));
            assertEquals(Failure.TLS, failure(() -> wrongCa.authenticate("dina", "Sandi-Dina-1")));
        }
    }

    @Test
    @DisplayName("nama server tidak cocok dengan sertifikatnya → ditolak, walau CA-nya dipercaya")
    void hostnameMismatch() throws Exception {
        try (FakeActiveDirectory elsewhere = new FakeActiveDirectory("dns:dc99.lain.test")) {
            elsewhere.addUser("dina", "Sandi-Dina-1", "Dina Aulia");
            ActiveDirectory d = new ActiveDirectory(props(elsewhere.url(), UPN_SUFFIX, null, null, null, elsewhere.trustPem()));
            assertEquals(Failure.TLS, failure(() -> d.authenticate("dina", "Sandi-Dina-1")));
        }
    }

    @Test
    @DisplayName("domain controller tidak terjangkau: UNREACHABLE, dalam batas waktu")
    void unreachable() throws Exception {
        ActiveDirectory d = new ActiveDirectory(props("ldaps://localhost:" + FakeActiveDirectory.unusedPort(), UPN_SUFFIX,
                null, null, null, ad.trustPem()));
        long start = System.nanoTime();
        assertEquals(Failure.UNREACHABLE, failure(() -> d.authenticate("dina", "Sandi-Dina-1")));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
    }

    // -------------------------------------------------------------------- setelan

    @Test
    @DisplayName("setelan diperiksa saat mulai HANYA kalau diaktifkan; pesannya menyebut variabelnya")
    void validation() throws Exception {
        assertDoesNotThrow(() -> new ActiveDirectory(AdProperties.disabled()));
        assertFalse(new ActiveDirectory(AdProperties.disabled()).enabled());

        String ldap = assertThrows(IllegalStateException.class, () -> new ActiveDirectory(
                props("ldap://dc01.uji.local:389", UPN_SUFFIX, null, null, null, null))).getMessage();
        assertTrue(ldap.contains("AD_LDAP_URL harus ldaps://"), ldap);

        String missing = assertThrows(IllegalStateException.class, () -> new ActiveDirectory(new AdProperties(true, null,
                null, null, null, "(cn=x)", null, "CN=svc", null, null, null, null, null))).getMessage();
        for (String needed : new String[]{"AD_LDAP_URL wajib", "AD_BASE_DN wajib", "harus memuat {0}", "AD_BIND_PASSWORD wajib"}) {
            assertTrue(missing.contains(needed), needed + " — " + missing);
        }

        String neither = assertThrows(IllegalStateException.class, () -> new ActiveDirectory(
                props(ad.url(), null, null, null, null, ad.trustPem()))).getMessage();
        assertTrue(neither.contains("AD_UPN_SUFFIX"), neither);

        Path notPem = Files.createTempFile("bukan", ".pem");
        Files.writeString(notPem, "bukan sertifikat");
        String trust = assertThrows(IllegalStateException.class, () -> new ActiveDirectory(
                props(ad.url(), UPN_SUFFIX, null, null, null, notPem))).getMessage();
        assertTrue(trust.contains("AD_TRUST_STORE"), trust);
    }

    // -------------------------------------------------------------------- alat

    @Test
    @DisplayName("objectGUID: urutan bita AD (tiga bagian pertama little-endian) ↔ UUID")
    void guidByteOrder() {
        UUID guid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        byte[] bytes = HexFormat.of().parseHex("33221100554477668899aabbccddeeff");

        assertArrayEquals(bytes, ActiveDirectory.guidToBytes(guid));
        assertEquals(guid, ActiveDirectory.guidFromBytes(bytes));
        assertArrayEquals(FakeActiveDirectory.guidBytes(dinaGuid), ActiveDirectory.guidToBytes(dinaGuid));
    }

    @Test
    @DisplayName("kode sub-galat AD di pesan bind")
    void subcodes() {
        assertEquals(Failure.INVALID_CREDENTIALS, ActiveDirectory.failureOf(new AuthenticationException("[LDAP: error code 49 - 80090308: LdapErr: DSID-0C09042A, comment: AcceptSecurityContext error, data 52e, v4563]")));
        assertEquals(Failure.PASSWORD_EXPIRED, ActiveDirectory.failureOf(new AuthenticationException("... data 532, v4563")));
        assertEquals(Failure.PASSWORD_MUST_CHANGE, ActiveDirectory.failureOf(new AuthenticationException("... data 773, v4563")));
        assertEquals(Failure.ACCOUNT_EXPIRED, ActiveDirectory.failureOf(new AuthenticationException("... data 701, v4563")));
        assertEquals(Failure.LOGON_RESTRICTED, ActiveDirectory.failureOf(new AuthenticationException("... data 531, v4563")));
        assertEquals(Failure.INVALID_CREDENTIALS, ActiveDirectory.failureOf(new AuthenticationException("tanpa kode")));
    }
}
