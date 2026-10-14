package com.caizhitong.security;
import java.util.Optional;
public interface SessionStore {
    Optional<SessionRecord> find(String digest, SessionKind kind);
    void create(SessionRecord record);
    boolean rotate(SessionRecord old, SessionRecord next);
    void revoke(SessionRecord record);
}
