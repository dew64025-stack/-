package com.caizhitong.common.ratelimit;

import com.caizhitong.common.BusinessException;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {
    @Test void exceedLimitIs429AndNewWindowWorks() {
        var instant = new Instant[]{Instant.parse("2026-10-13T00:00:00Z")};
        RateCounter remoteStore = new RateCounter() {
            Instant expires = Instant.MIN; long count;
            public long increment(String bucket, Duration window) {
                if (!instant[0].isBefore(expires)) { count = 0; expires = instant[0].plus(window); }
                return ++count;
            }
        };
        RateLimiter limiter = new RateLimiter(remoteStore);
        limiter.check("login:ip", 2, Duration.ofSeconds(60)); limiter.check("login:ip", 2, Duration.ofSeconds(60));
        assertEquals(429, assertThrows(BusinessException.class, () -> limiter.check("login:ip", 2, Duration.ofSeconds(60))).getStatus());
        instant[0] = instant[0].plusSeconds(60);
        assertDoesNotThrow(() -> limiter.check("login:ip", 2, Duration.ofSeconds(60)));
    }
    @Test void RedisFailureCannotGrantAccess() {
        RateLimiter limiter = new RateLimiter((bucket, window) -> { throw new org.springframework.dao.DataAccessResourceFailureException("offline"); });
        assertEquals(503, assertThrows(BusinessException.class, () -> limiter.check("bucket", 1, Duration.ofMinutes(1))).getStatus());
    }
}
