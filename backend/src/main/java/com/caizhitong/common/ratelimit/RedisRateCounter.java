package com.caizhitong.common.ratelimit;

import com.caizhitong.common.SensitiveDataCrypto;
import java.time.Duration;
import java.util.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisRateCounter implements RateCounter {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);
    private final StringRedisTemplate redis;
    private final SensitiveDataCrypto crypto;
    public RedisRateCounter(StringRedisTemplate redis, SensitiveDataCrypto crypto) { this.redis = redis; this.crypto = crypto; }
    @Override public long increment(String bucket, Duration window) {
        String key = "rate:" + HexFormat.of().formatHex(crypto.searchHash("rate-bucket", bucket));
        Long count = redis.execute(INCREMENT, List.of(key), Long.toString(window.toMillis()));
        if (count == null || count < 1) throw new DataAccessResourceFailureException("Rate counter unavailable");
        return count;
    }
}
