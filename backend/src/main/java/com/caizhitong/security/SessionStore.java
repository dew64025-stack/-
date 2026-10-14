package com.caizhitong.security;
import java.util.Optional;
public interface SessionStore {
    Optional<SessionRecord> find(String digest, SessionKind kind);
    void create(SessionRecord record);
    boolean rotate(SessionRecord old, SessionRecord next);
    /** True only when the atomic operation matched and revoked this session generation. */
    boolean revoke(SessionRecord record);
}
