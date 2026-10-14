package com.caizhitong.security;
import java.util.Set;
public record Actor(long userId, long campusId, Set<String> permissions, boolean phoneBound,
        boolean studentVerified, String status, SessionKind kind) {
    public Actor { permissions = Set.copyOf(permissions); }
}
