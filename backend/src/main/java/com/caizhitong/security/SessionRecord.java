package com.caizhitong.security;
public record SessionRecord(long userId, long campusId, SessionKind kind, String sessionId,
        String accessDigest, String refreshDigest) {}
