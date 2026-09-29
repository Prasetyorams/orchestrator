package id.jakforge.openorchestrator.model;

import id.jakforge.openorchestrator.common.SecretRedaction;
import id.jakforge.openorchestrator.security.MachineKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Aturan kecil Robot Agent: percobaan ulang, versi, machine key, sensor rahasia. */
class AgentRulesTest {

    @Test
    @DisplayName("percobaan ulang otomatis hanya untuk kegagalan infrastruktur SEBELUM workflow berjalan (W3)")
    void retryOnlyBeforeWorkflowRan() {
        assertTrue(RetryPolicy.shouldRetry(AgentErrorCodes.SESSION_PREPARATION_FAILED, false, 1, 1, false));
        assertTrue(RetryPolicy.shouldRetry(AgentErrorCodes.LEASE_EXPIRED, false, 1, 1, false));

        assertFalse(RetryPolicy.shouldRetry(AgentErrorCodes.EXECUTOR_CRASHED, true, 1, 1, false),
                "workflow sudah berjalan: bisa saja email sudah terkirim");
        assertFalse(RetryPolicy.shouldRetry(AgentErrorCodes.WORKFLOW_FAILED, false, 1, 2, false));
        assertFalse(RetryPolicy.shouldRetry(AgentErrorCodes.LOGON_FAILED, false, 1, 2, false),
                "mengulang login yang salah hanya mengunci akunnya");
        assertFalse(RetryPolicy.shouldRetry(AgentErrorCodes.TIMEOUT, false, 1, 2, false));
        assertFalse(RetryPolicy.shouldRetry("KodeBaruDariAgent", false, 1, 2, false));
    }

    @Test
    @DisplayName("jumlah percobaan ulang mengikuti setelan proses, paling banyak 2")
    void retryLimit() {
        assertTrue(RetryPolicy.shouldRetry(AgentErrorCodes.AGENT_RESTARTED, false, 1, 1, false));
        assertFalse(RetryPolicy.shouldRetry(AgentErrorCodes.AGENT_RESTARTED, false, 2, 1, false));
        assertFalse(RetryPolicy.shouldRetry(AgentErrorCodes.AGENT_RESTARTED, false, 1, 0, false), "0 = mati");
        assertFalse(RetryPolicy.shouldRetry(AgentErrorCodes.AGENT_RESTARTED, false, 3, 9, false));
        assertFalse(RetryPolicy.shouldRetry(AgentErrorCodes.AGENT_RESTARTED, false, 1, 1, true), "sudah diulang");
    }

    @Test
    @DisplayName("versi agent dibandingkan per angka, bukan sebagai teks")
    void versions() {
        assertTrue(AgentVersions.isOlderThan("1.9.0", "1.10.0"));
        assertFalse(AgentVersions.isOlderThan("1.10.0", "1.9.0"));
        assertFalse(AgentVersions.isOlderThan("1.2", "1.2.0"));
        assertFalse(AgentVersions.isOlderThan("v2.0.0-beta+7", "1.0.0"));
        assertTrue(AgentVersions.isOlderThan("bukan-versi", "1.0.0"));
        assertTrue(AgentVersions.isOlderThan(null, "1.0.0"));
    }

    @Test
    @DisplayName("machine key: acak, berawalan oo_mk_, hanya hash-nya yang disimpan")
    void machineKeys() {
        MachineKeys.Generated first = MachineKeys.generate();
        MachineKeys.Generated second = MachineKeys.generate();

        assertTrue(first.key().startsWith(MachineKeys.PREFIX));
        assertTrue(MachineKeys.looksValid(first.key()));
        assertNotEquals(first.key(), second.key());
        assertEquals(64, first.hash().length(), "SHA-256 heksa");
        assertEquals(first.hash(), MachineKeys.hash(first.key()));
        assertTrue(first.key().startsWith(first.displayPrefix()));
        assertFalse(first.hash().contains(first.key()));
        assertFalse(MachineKeys.looksValid("oo_mk_pendek"));
    }

    @Test
    @DisplayName("token dan machine key di pesan catatan disensor")
    void redaction() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnopqrstuvwxyz0123";
        String key = MachineKeys.generate().key();

        String redacted = SecretRedaction.redact("gagal: Authorization: Bearer " + jwt + " dengan kunci " + key);

        assertFalse(redacted.contains(jwt));
        assertFalse(redacted.contains(key));
        assertTrue(redacted.contains("[token disensor]"));
        assertTrue(redacted.contains("[kunci disensor]"));
        assertEquals("Klik 'Simpan'.", SecretRedaction.redact("Klik 'Simpan'."));
    }
}
