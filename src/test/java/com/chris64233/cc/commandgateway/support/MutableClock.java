package com.chris64233.cc.commandgateway.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public class MutableClock extends Clock {

    private Instant instant;

    public MutableClock() {
        this(Instant.parse("2026-09-26T00:00:00Z"));
    }

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void advance(Duration duration) {
        this.instant = instant.plus(duration);
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }
}
