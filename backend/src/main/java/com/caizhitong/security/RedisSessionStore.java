package com.caizhitong.security;

import java.util.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisSessionStore implements SessionStore {
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1], KEYS[2]) > 0 then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', 900)
            redis.call('SET', KEYS[2], ARGV[1], 'EX', 604800)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
            if redis.call('EXISTS', KEYS[3], KEYS[4]) > 0 then return 0 end
            redis.call('DEL', KEYS[1], KEYS[2])
            redis.call('SET', KEYS[3], ARGV[2], 'EX', 900)
            redis.call('SET', KEYS[4], ARGV[2], 'EX', 604800)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] or redis.call('GET', KEYS[2]) == ARGV[1] then
                redis.call('DEL', KEYS[1], KEYS[2])
                return 1
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    public RedisSessionStore(StringRedisTemplate redis) { this.redis = redis; }
    @Override public Optional<SessionRecord> find(String digest, SessionKind kind) {
        String value = redis.opsForValue().get(key(kind, digest));
        if (value == null) return Optional.empty();
        try {
            String[] fields = value.split("\\|", -1);
            if (fields.length != 6 || !fields[2].equals(kind.name())) throw new IllegalArgumentException();
            return Optional.of(new SessionRecord(Long.parseLong(fields[0]), Long.parseLong(fields[1]), kind, fields[3], fields[4], fields[5]));
        } catch (IllegalArgumentException e) { throw new DataAccessResourceFailureException("Invalid session store data"); }
    }
    @Override public void create(SessionRecord record) {
        Long result = redis.execute(CREATE, keys(record), value(record));
        if (!Long.valueOf(1).equals(result)) throw new DataAccessResourceFailureException("Session creation failed");
    }
    @Override public boolean rotate(SessionRecord old, SessionRecord next) {
        List<String> keys = new ArrayList<>(keys(old)); keys.addAll(keys(next));
        Long result = redis.execute(ROTATE, keys, value(old), value(next));
        if (result == null) throw new DataAccessResourceFailureException("Session rotation failed");
        return result == 1;
    }
    @Override public boolean revoke(SessionRecord record) {
        Long result = redis.execute(REVOKE, keys(record), value(record));
        if (result == null) throw new DataAccessResourceFailureException("Session revocation failed");
        return result == 1;
    }
    private String key(SessionKind kind, String digest) { return "session:{" + kind + "}:" + digest; }
    private List<String> keys(SessionRecord r) { return List.of(key(r.kind(), r.accessDigest()), key(r.kind(), r.refreshDigest())); }
    private String value(SessionRecord r) { return r.userId() + "|" + r.campusId() + "|" + r.kind() + "|" + r.sessionId() + "|" + r.accessDigest() + "|" + r.refreshDigest(); }
}
