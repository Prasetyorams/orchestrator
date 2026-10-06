package id.jakforge.openorchestrator.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bahan masuk Open Assistant lewat dasbor: PKCE, rahasia acak, tautan kembali, nama robot. */
class AssistantSignInTest {

    /** RFC 7636 lampiran B. */
    private static final String RFC_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String RFC_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    @DisplayName("code_challenge S256 sama dengan contoh RFC 7636")
    void challengeMatchesRfcVector() {
        assertEquals(RFC_CHALLENGE, AssistantSignIn.challengeOf(RFC_VERIFIER));
        assertTrue(AssistantSignIn.verifierMatches(RFC_VERIFIER, RFC_CHALLENGE));
    }

    @Test
    @DisplayName("verifier yang salah, terlalu pendek, atau challenge yang rusak ditolak")
    void wrongVerifierRejected() {
        assertFalse(AssistantSignIn.verifierMatches(RFC_VERIFIER.replace('d', 'e'), RFC_CHALLENGE));
        assertFalse(AssistantSignIn.verifierMatches("pendek", AssistantSignIn.challengeOf("pendek")));
        assertFalse(AssistantSignIn.verifierMatches(RFC_VERIFIER, RFC_CHALLENGE + "x"));
        assertFalse(AssistantSignIn.verifierMatches(null, RFC_CHALLENGE));
        assertFalse(AssistantSignIn.verifierMatches(RFC_VERIFIER, null));
        assertFalse(AssistantSignIn.verifierMatches(RFC_VERIFIER + " ", RFC_CHALLENGE));
    }

    @Test
    @DisplayName("rahasia: 256 bit acak dalam base64url, tidak pernah sama, hash-nya bukan dirinya")
    void secretsAreRandomAndHashed() {
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            String secret = AssistantSignIn.newSecret();
            assertTrue(secret.matches("[A-Za-z0-9_-]{43}"), secret);
            assertTrue(seen.add(secret));
            assertEquals(64, AssistantSignIn.hash(secret).length());
            assertNotEquals(secret, AssistantSignIn.hash(secret));
        }
    }

    @Test
    @DisplayName("tautan kembali hanya ke openassistant://signin, kode dan state disandikan")
    void redirectIsFixedAndEncoded() {
        assertEquals("openassistant://signin?code=abc_-DEF&state=st%7Eate.1",
                AssistantSignIn.redirect("abc_-DEF", "st~ate.1"));
    }

    @Test
    @DisplayName("state: penanda acak url-safe 8–512 karakter; yang lain ditolak")
    void stateShape() {
        assertTrue(AssistantSignIn.isState("abcDEF12"));
        assertTrue(AssistantSignIn.isState(AssistantSignIn.newSecret()));
        assertFalse(AssistantSignIn.isState("pendek"));
        assertFalse(AssistantSignIn.isState("ada spasi di sini"));
        assertFalse(AssistantSignIn.isState("<script>alert(1)</script>"));
        assertFalse(AssistantSignIn.isState("x".repeat(513)));
        assertFalse(AssistantSignIn.isState(null));
    }

    @Test
    @DisplayName("nama robot: pengguna-komputer, karakter aneh menjadi tanda hubung, panjang dibatasi")
    void robotNames() {
        assertEquals("fajar-DESKTOP-ILR0BGM", AssistantSignIn.robotNameFor("fajar", "DESKTOP-ILR0BGM"));
        assertEquals("budi.santoso-PC-kantor-2", AssistantSignIn.robotNameFor("budi.santoso", "PC kantor #2"));
        assertEquals("pengguna-pc", AssistantSignIn.robotNameFor("  ", "///"));
        assertEquals("ani-LAPTOP", AssistantSignIn.robotNameFor("--ani--", ".LAPTOP."));
        assertEquals(60 + 1 + 60, AssistantSignIn.robotNameFor("a".repeat(100), "b".repeat(100)).length());
    }
}
