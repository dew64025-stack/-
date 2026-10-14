package com.caizhitong.security;
import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.common.BusinessException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
@Service
public class SessionTokenService {
    private final SessionStore store;
    private final ActorLoader loader;
    private final SensitiveDataCrypto crypto;
    private final SecureRandom random = new SecureRandom();
    public SessionTokenService(SessionStore store, ActorLoader loader, SensitiveDataCrypto crypto) {
        this.store = store; this.loader = loader; this.crypto = crypto;
    }
    public SessionTokens issue(long user, long campus, SessionKind kind) {
        requireActor(user, campus, kind);
        SessionTokens tokens = tokens();
        try { store.create(record(user, campus, kind, tokens)); }
        catch (DataAccessException e) { throw unavailable(); }
        return tokens;
    }
    public Optional<Actor> authenticate(String token, SessionKind kind) {
        if (!validToken(token)) return Optional.empty();
        try {
            return store.find(digest(token, kind), kind)
                    .filter(record -> record.accessDigest().equals(digest(token, kind)))
                    .flatMap(record -> loader.load(record.userId(), record.campusId(), kind))
                    .filter(actor -> actor.kind() == kind);
        } catch (DataAccessException e) { throw unavailable(); }
    }
    public SessionTokens refresh(String token, SessionKind kind) {
        if (!validToken(token)) throw unauthenticated();
        try {
            String digest = digest(token, kind);
            SessionRecord old = store.find(digest, kind).filter(value -> value.refreshDigest().equals(digest))
                    .orElseThrow(SessionTokenService::unauthenticated);
            requireActor(old.userId(), old.campusId(), kind);
            SessionTokens tokens = tokens();
            if (!store.rotate(old, record(old.userId(), old.campusId(), kind, tokens))) throw unauthenticated();
            return tokens;
        } catch (DataAccessException e) { throw unavailable(); }
    }
    public void revoke(String token, SessionKind kind) {
        if (!validToken(token)) throw unauthenticated();
        try { store.revoke(store.find(digest(token, kind), kind).orElseThrow(SessionTokenService::unauthenticated)); }
        catch (DataAccessException e) { throw unavailable(); }
    }
    private Actor requireActor(long user, long campus, SessionKind kind) {
        return loader.load(user, campus, kind).filter(actor -> actor.kind() == kind && actor.userId() == user && actor.campusId() == campus)
                .orElseThrow(SessionTokenService::unauthenticated);
    }
    private SessionTokens tokens() { return new SessionTokens(randomToken(), 900, randomToken(), 604800); }
    private SessionRecord record(long user, long campus, SessionKind kind, SessionTokens tokens) {
        return new SessionRecord(user, campus, kind, randomToken(), digest(tokens.accessToken(), kind), digest(tokens.refreshToken(), kind));
    }
    private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String digest(String token, SessionKind kind) { return HexFormat.of().formatHex(crypto.searchHash("session:" + kind, token)); }
    private boolean validToken(String token) { return token != null && token.matches("[A-Za-z0-9_-]{43}"); }
    private static BusinessException unauthenticated() { return new BusinessException(401, "UNAUTHENTICATED", "会话无效，请重新登录"); }
    private static BusinessException unavailable() { return new BusinessException(503, "SESSION_UNAVAILABLE", "会话服务暂时不可用", null, true); }
}
