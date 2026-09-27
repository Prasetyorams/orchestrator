package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.dto.request.ChangePasswordRequest;
import id.jakforge.forgehub.dto.request.LoginRequest;
import id.jakforge.forgehub.dto.request.UpdateProfileRequest;
import id.jakforge.forgehub.dto.response.LoginResponse;
import id.jakforge.forgehub.model.UserAccess;
import id.jakforge.forgehub.repository.UserRepository;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.security.JwtService;
import id.jakforge.forgehub.security.Passwords;
import id.jakforge.forgehub.security.PermissionService;
import id.jakforge.forgehub.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Masuk, profil, dan kata sandi milik pengguna yang sedang masuk.
 *
 * <p>Repositorinya palsu, bukan mock: yang diuji aturan di layanan, dan
 * repositori palsu cukup mencatat apa yang diminta tanpa menyeret agen
 * bytecode ke uji yang tidak membutuhkannya.
 *
 * <p>Pemeriksaan bentuk isian — wajib diisi, panjang maksimal — ada di anotasi
 * permintaannya dan diuji di {@code RequestValidationTest}.
 */
class AuthServiceTest {

    private static final String ADMIN_PASSWORD = "sandi-benar-123";

    private final ForgeHubPrincipal principal =
            new ForgeHubPrincipal(UUID.randomUUID(), UUID.randomUUID(), "FH_Admin", "Administrator");

    private final FakeUserRepository users = new FakeUserRepository();
    private final JwtService jwtService = new JwtService("k".repeat(32), 60);

    private final AuthService authService = new AuthService(users, jwtService,
            new PermissionService(users, TestProperties.defaults(), Clock.systemUTC()));

    // ---------- masuk ----------

    @Test
    @DisplayName("masuk dengan kata sandi benar mengembalikan token dan identitasnya")
    void loginReturnsTokenAndIdentity() {
        users.storeLoginRow(true, Passwords.hash(ADMIN_PASSWORD));

        LoginResponse login = authService.login(new LoginRequest("FH_Admin", ADMIN_PASSWORD));

        assertEquals("FH_Admin", jwtService.parsePrincipal(login.token()).username());
        assertEquals(60, login.expiresInMinutes());
        assertEquals("Administrator", login.role());
        assertEquals("default", login.tenantName());
        assertTrue(users.loginRecorded);
    }

    @Test
    @DisplayName("kata sandi salah, akun nonaktif, dan pengguna tak dikenal dijawab SAMA: 401")
    void rejectedLoginsLookTheSame() {
        users.storeLoginRow(true, Passwords.hash(ADMIN_PASSWORD));
        ApiException wrongPassword = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest("FH_Admin", "tebakan")));

        users.storeLoginRow(false, Passwords.hash(ADMIN_PASSWORD));
        ApiException inactive = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest("FH_Admin", ADMIN_PASSWORD)));

        users.loginRow = null;
        ApiException unknown = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest("siapa", ADMIN_PASSWORD)));

        for (ApiException error : List.of(wrongPassword, inactive, unknown)) {
            assertEquals(HttpStatus.UNAUTHORIZED, error.status());
            assertEquals("Nama pengguna atau kata sandi salah.", error.getMessage());
        }
    }

    @Test
    @DisplayName("hash dengan putaran lama di-hash ulang saat berhasil masuk")
    void weakHashIsUpgradedOnLogin() {
        String weakHash = weakHashOf(ADMIN_PASSWORD);
        users.storeLoginRow(true, weakHash);

        authService.login(new LoginRequest("FH_Admin", ADMIN_PASSWORD));

        assertTrue(Passwords.needsRehash(weakHash));
        assertTrue(Passwords.verify(ADMIN_PASSWORD, users.newHash));
        assertFalse(Passwords.needsRehash(users.newHash));
    }

    // ---------- profil ----------

    @Test
    @DisplayName("profil tersimpan sesudah dipangkas, dan yang dikembalikan profil dari basis data beserta izinnya")
    void profileIsSavedTrimmed() {
        Map<String, Object> profile = authService.updateProfile(principal,
                new UpdateProfileRequest("  Pras  ", " pras@contoh.id "));

        assertArrayEquals(new String[] { "Pras", "pras@contoh.id" }, users.lastProfile);
        assertEquals("FH_Admin", profile.get("username"));
        assertEquals(List.of(), profile.get("permissions"));
    }

    @Test
    @DisplayName("surel yang dikosongkan berarti dihapus")
    void emptyEmailIsCleared() {
        authService.updateProfile(principal, new UpdateProfileRequest("Pras", ""));

        assertEquals("Pras", users.lastProfile[0]);
        assertNull(users.lastProfile[1]);
    }

    @Test
    @DisplayName("surel yang bentuknya salah ditolak 400, dan tidak ada yang disimpan")
    void malformedEmailIsRejected() {
        for (String invalidEmail : new String[] { "pras.contoh.id", "pras@contoh", "pras @contoh.id", "@contoh.id" }) {
            assertEquals(HttpStatus.BAD_REQUEST, statusOf(() ->
                    authService.updateProfile(principal, new UpdateProfileRequest("Pras", invalidEmail))), invalidEmail);
        }

        assertNull(users.lastProfile);
    }

    @Test
    @DisplayName("pengguna yang sudah dihapus dijawab 401")
    void deletedUserIsUnauthorized() {
        users.changedRows = 0;

        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(() ->
                authService.updateProfile(principal, new UpdateProfileRequest("Pras", null))));
    }

    // ---------- kata sandi ----------

    @Test
    @DisplayName("kata sandi lama yang salah dijawab 400, BUKAN 401 yang membuat klien keluar")
    void wrongCurrentPasswordIsBadRequest() {
        users.storedHash = Passwords.hash("sandi-lama-benar");

        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() ->
                authService.changePassword(principal, new ChangePasswordRequest("tebakan-salah", "sandi-baru-123"))));

        assertNull(users.newHash);
    }

    @Test
    @DisplayName("kata sandi lama yang benar mengganti hash dengan sandi baru")
    void correctCurrentPasswordReplacesHash() {
        users.storedHash = Passwords.hash("sandi-lama-benar");

        assertEquals("OK", authService.changePassword(principal,
                new ChangePasswordRequest("sandi-lama-benar", "sandi-baru-123")).status());

        assertTrue(Passwords.verify("sandi-baru-123", users.newHash));
    }

    // ---------- alat ----------

    private static HttpStatus statusOf(Executable action) {
        return assertThrows(ApiException.class, action).status();
    }

    /**
     * Hash PBKDF2 yang sah tapi dengan putaran di bawah standar sekarang —
     * bentuk hash lama yang harus di-hash ulang begitu pemiliknya masuk.
     */
    private static String weakHashOf(String password) {
        int weakIterations = 1000;
        byte[] salt = new byte[16];

        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, weakIterations, 256);
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();

            return "pbkdf2-sha256$" + weakIterations + "$" + Base64.getEncoder().encodeToString(salt)
                    + "$" + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mencatat apa yang diminta layanan, tanpa basis data. */
    private static final class FakeUserRepository extends UserRepository {

        Map<String, Object> loginRow;
        boolean loginRecorded;
        String storedHash;
        String newHash;
        String[] lastProfile;
        int changedRows = 1;

        FakeUserRepository() {
            super(null);
        }

        void storeLoginRow(boolean active, String passwordHash) {
            loginRow = new HashMap<>();
            loginRow.put("id", UUID.randomUUID().toString());
            loginRow.put("username", "FH_Admin");
            loginRow.put("passwordHash", passwordHash);
            loginRow.put("displayName", "ForgeHub Administrator");
            loginRow.put("role", "Administrator");
            loginRow.put("isActive", active);
            loginRow.put("tenantId", UUID.randomUUID().toString());
            loginRow.put("tenantName", "default");
        }

        @Override
        public Optional<Map<String, Object>> findForLogin(String username) {
            return Optional.ofNullable(loginRow);
        }

        @Override
        public void recordLogin(UUID userId) {
            loginRecorded = true;
        }

        @Override
        public int updateProfile(UUID userId, UUID tenantId, String displayName, String email) {
            if (changedRows > 0) lastProfile = new String[] { displayName, email };
            return changedRows;
        }

        @Override
        public Optional<Map<String, Object>> findProfile(UUID userId, UUID tenantId) {
            return Optional.of(Map.of("username", "FH_Admin"));
        }

        @Override
        public Optional<String> findPasswordHash(UUID userId) {
            return Optional.ofNullable(storedHash);
        }

        @Override
        public void updatePasswordHash(UUID userId, String passwordHash) {
            newHash = passwordHash;
        }

        @Override
        public Optional<UserAccess> findAccess(UUID userId, UUID tenantId) {
            return Optional.empty();
        }
    }
}
