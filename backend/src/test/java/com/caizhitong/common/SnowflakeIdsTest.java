package com.caizhitong.common;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.HashSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SnowflakeIdsTest {
    @Test void encodesEpochWorkerAndSequence() {
        var ids = new SnowflakeIds(Clock.fixed(Instant.parse("2026-01-01T00:00:00.001Z"), ZoneOffset.UTC), 7);
        assertEquals((1L << 22) | (7L << 12), ids.nextId());
        assertEquals((1L << 22) | (7L << 12) | 1, ids.nextId());
    }

    @Test void rejectsInvalidWorkerAndTimestampRange() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIds(Clock.systemUTC(), -1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIds(Clock.systemUTC(), 1024));
        assertThrows(IllegalStateException.class, () -> new SnowflakeIds(Clock.fixed(Instant.parse("2025-12-31T23:59:59Z"), ZoneOffset.UTC), 0).nextId());
        assertThrows(IllegalStateException.class, () -> new SnowflakeIds(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z").plusMillis(1L << 41), ZoneOffset.UTC), 0).nextId());
    }

    @Test void clockRollbackIsRejectedWithoutReusingId() {
        var clock = new MutableClock();
        var ids = new SnowflakeIds(clock, 1);
        long first = ids.nextId();
        clock.millis.decrementAndGet();
        assertThrows(IllegalStateException.class, ids::nextId);
        clock.millis.incrementAndGet();
        assertTrue(ids.nextId() > first);
    }

    @Test void concurrentCallsProduceUniqueIds() throws Exception {
        var ids = new SnowflakeIds(Clock.systemUTC(), 1023);
        var unique = ConcurrentHashMap.<Long>newKeySet();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var jobs = new HashSet<Future<?>>();
            for (int thread = 0; thread < 8; thread++) {
                jobs.add(executor.submit(() -> {
                    for (int i = 0; i < 2000; i++) assertTrue(unique.add(ids.nextId()));
                }));
            }
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
        }
        assertEquals(16000, unique.size());
    }

    @Test void sequenceExhaustionDoesNotHangOrReuseIds() {
        var ids = new SnowflakeIds(Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC), 0);
        var unique = new HashSet<Long>();
        for (int i = 0; i < 4096; i++) assertTrue(unique.add(ids.nextId()));
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(IllegalStateException.class, ids::nextId));
    }

    static class MutableClock extends Clock {
        final AtomicLong millis = new AtomicLong(Instant.parse("2026-10-13T00:00:00Z").toEpochMilli());
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        @Override public long millis() { return millis.get(); }
    }
}
