package id.jakforge.openorchestrator.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Bentuk waktu yang dikirim ke klien: untai ISO-8601 UTC dengan enam digit
 * pecahan detik, mis. {@code 2026-09-27T03:04:05.123456Z}.
 *
 * <p>OpenOrchestrator .NET menyimpan waktu sebagai teks dan mengirimkannya apa adanya,
 * jadi dasbor dan robot sudah menerima untai berbentuk ini. Satu formatter di
 * satu tempat menjaga bentuknya tidak berubah hanya karena setelan Jackson
 * berubah.
 */
public final class Timestamps {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private Timestamps() {
    }

    public static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Sekarang, dalam bentuk yang sama dengan yang dikirim ke klien. */
    public static String nowText() {
        return format(nowUtc());
    }

    public static String format(OffsetDateTime time) {
        return ISO_UTC.format(time.withOffsetSameInstant(ZoneOffset.UTC));
    }

    public static String format(Instant instant) {
        return ISO_UTC.format(instant.atOffset(ZoneOffset.UTC));
    }
}
