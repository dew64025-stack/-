package com.caizhitong.security;
public record SessionTokens(String accessToken, long expiresIn, String refreshToken, long refreshExpiresIn) {
    @Override public String toString() { return "SessionTokens[redacted]"; }
}
