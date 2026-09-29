package id.jakforge.openorchestrator.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 dalam heksa kecil — bentuk yang sama dengan {@code encode(sha256(...), 'hex')} di PostgreSQL. */
public final class Hashes {

    private Hashes() {
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 tidak tersedia.", e);
        }
    }
}
