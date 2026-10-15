package com.caizhitong.common.ratelimit;
import java.time.Duration;
import com.caizhitong.common.BusinessException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
@Component
public class RateLimiter {
    private final RateCounter counter;
    public RateLimiter(RateCounter counter) { this.counter = counter; }
    public void check(String bucket, int max, Duration window) {
        if (bucket == null || bucket.isBlank() || max < 1 || window == null || window.isNegative() || window.isZero() || window.toMillis() < 1)
            throw new IllegalArgumentException("Invalid rate limit configuration");
        try {
            if (counter.increment(bucket, window) > max) throw new BusinessException(429, "RATE_LIMITED", "请求过于频繁，请稍后重试", null, true);
        } catch (DataAccessException e) { throw new BusinessException(503, "RATE_LIMIT_UNAVAILABLE", "限流服务暂时不可用", null, true); }
    }
}
