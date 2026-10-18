package com.caizhitong.testsupport;

import com.caizhitong.security.SessionKind;
import com.caizhitong.security.SessionRecord;
import com.caizhitong.security.SessionStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** In-process session store so HTTP tests do not depend on Redis. */
public class InMemorySessionStore implements SessionStore {
    private final Map<String, SessionRecord> data = new HashMap<>();

    private static String key(String digest, SessionKind kind) {
        return kind + ":" + digest;
    }

    @Override
    public synchronized void create(SessionRecord record) {
        data.put(key(record.accessDigest(), record.kind()), record);
        data.put(key(record.refreshDigest(), record.kind()), record);
    }

    @Override
    public synchronized Optional<SessionRecord> find(String digest, SessionKind kind) {
        return Optional.ofNullable(data.get(key(digest, kind))).filter(record -> record.kind() == kind);
    }

    @Override
    public synchronized boolean rotate(SessionRecord old, SessionRecord next) {
        if (find(old.refreshDigest(), old.kind()).filter(old::equals).isEmpty()) return false;
        revoke(old);
        create(next);
        return true;
    }

    @Override
    public synchronized boolean revoke(SessionRecord record) {
        boolean access = data.remove(key(record.accessDigest(), record.kind())) != null;
        boolean refresh = data.remove(key(record.refreshDigest(), record.kind())) != null;
        return access || refresh;
    }
}
