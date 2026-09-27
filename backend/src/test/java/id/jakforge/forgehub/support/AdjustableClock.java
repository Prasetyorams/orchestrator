package id.jakforge.forgehub.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Jam yang berhenti di satu saat dan hanya maju saat disuruh — untuk menguji kedaluwarsa. */
public final class AdjustableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public AdjustableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId otherZone) {
        return new AdjustableClock(now, otherZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
