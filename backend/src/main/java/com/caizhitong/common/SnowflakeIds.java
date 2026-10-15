package com.caizhitong.common;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

public final class SnowflakeIds {
    private static final long EPOCH = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long MAX_TIMESTAMP = (1L << 41) - 1;
    private final Clock clock;
    private final int workerId;
    private long lastTimestamp = -1;
    private int sequence;

    public SnowflakeIds(Clock clock, int workerId) {
        if (workerId < 0 || workerId > 1023) throw new IllegalArgumentException("Worker ID must be 0..1023");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long timestamp = clock.millis() - EPOCH;
        if (timestamp < 0 || timestamp > MAX_TIMESTAMP) throw new IllegalStateException("Clock is outside the ID epoch range");
        if (timestamp < lastTimestamp) throw new IllegalStateException("Clock moved backwards");
        if (timestamp == lastTimestamp && sequence == 4095) timestamp = awaitNextTimestamp();
        if (timestamp == lastTimestamp) {
            sequence++;
        } else {
            sequence = 0;
        }
        lastTimestamp = timestamp;
        return (timestamp << 22) | ((long) workerId << 12) | sequence;
    }

    private long awaitNextTimestamp() {
        long deadline = System.nanoTime() + 100_000_000L;
        do {
            LockSupport.parkNanos(100_000L);
            long timestamp = clock.millis() - EPOCH;
            if (timestamp < lastTimestamp) throw new IllegalStateException("Clock moved backwards");
            if (timestamp > MAX_TIMESTAMP) throw new IllegalStateException("Clock is outside the ID epoch range");
            if (timestamp > lastTimestamp) return timestamp;
        } while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline);
        throw new IllegalStateException("Clock did not advance before ID capacity timeout");
    }
}
