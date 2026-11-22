package com.clinicqueue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * System clock plus a manual offset. In a live demo, consultations take seconds of real time,
 * so the "+10 min" button advances this clock and lets the predictor learn realistic durations.
 */
public class AdjustableClock extends Clock {

    private final ZoneId zone;
    private volatile Duration offset = Duration.ZERO;

    public AdjustableClock(ZoneId zone) {
        this.zone = zone;
    }

    public void advance(Duration d) {
        if (d.isNegative()) throw new IllegalArgumentException("Cannot go back in time");
        synchronized (this) {
            offset = offset.plus(d);
        }
    }

    public void resetOffset() {
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId z) {
        AdjustableClock c = new AdjustableClock(z);
        c.offset = this.offset;
        return c;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }
}
