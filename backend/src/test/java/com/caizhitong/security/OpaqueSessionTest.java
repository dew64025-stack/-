package com.caizhitong.security;

import com.caizhitong.common.BusinessException;
import com.caizhitong.common.SensitiveDataCrypto;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpaqueSessionTest {
    final MutableClock clock = new MutableClock();
    final MemorySessionStore store = new MemorySessionStore(clock);
    Actor actor = new Actor(10001, 1, Set.of(), true, true, "active", SessionKind.STUDENT);
    final SessionTokenService sessions = new SessionTokenService(store,
            (user, campus, kind) -> actor != null && actor.kind() == kind ? Optional.of(actor) : Optional.empty(), crypto());

    static SensitiveDataCrypto crypto() { byte[] hmac = new byte[32]; hmac[0] = 1; return new SensitiveDataCrypto(new byte[32], hmac); }

    @Test void forgedAndWrongNamespaceTokensNeverAuthenticate() {
        SessionTokens first = sessions.issue(10001, 1, SessionKind.STUDENT);
        assertTrue(sessions.authenticate("forged", SessionKind.STUDENT).isEmpty());
        assertTrue(sessions.authenticate(first.accessToken(), SessionKind.ADMIN).isEmpty());
        assertFalse(first.toString().contains(first.accessToken()));
        assertEquals(900, first.expiresIn());
        assertEquals(604800, first.refreshExpiresIn());
    }
    @Test void expirationRejectsAccessAndRestrictedIdentityLoadsCurrentDatabaseStatus() {
        SessionTokens first = sessions.issue(10001, 1, SessionKind.STUDENT);
        actor = new Actor(10001, 1, Set.of(), true, true, "disabled", SessionKind.STUDENT);
        assertEquals("disabled", sessions.authenticate(first.accessToken(), SessionKind.STUDENT).orElseThrow().status());
        actor = new Actor(10001, 1, Set.of(), true, true, "active", SessionKind.STUDENT);
        clock.now = clock.now.plusSeconds(900);
        assertTrue(sessions.authenticate(first.accessToken(), SessionKind.STUDENT).isEmpty());
    }
    @Test void refreshConsumesOnceAndRevokesOldAccessAndLogoutRevokesBoth() {
        SessionTokens first = sessions.issue(10001, 1, SessionKind.STUDENT);
        SessionTokens next = sessions.refresh(first.refreshToken(), SessionKind.STUDENT);
        assertEquals(401, assertThrows(BusinessException.class,
                () -> sessions.refresh(first.refreshToken(), SessionKind.STUDENT)).getStatus());
        assertTrue(sessions.authenticate(first.accessToken(), SessionKind.STUDENT).isEmpty());
        assertTrue(sessions.authenticate(next.accessToken(), SessionKind.STUDENT).isPresent());
        sessions.revoke(next.accessToken(), SessionKind.STUDENT);
        assertTrue(sessions.authenticate(next.accessToken(), SessionKind.STUDENT).isEmpty());
        assertThrows(BusinessException.class, () -> sessions.refresh(next.refreshToken(), SessionKind.STUDENT));
    }
    @Test void twoConcurrentRefreshesHaveExactlyOneWinner() throws Exception {
        SessionTokens first = sessions.issue(10001, 1, SessionKind.STUDENT);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> refresh = () -> { start.await(); try { sessions.refresh(first.refreshToken(), SessionKind.STUDENT); return true; }
                catch (BusinessException e) { assertEquals(401, e.getStatus()); return false; } };
            var a = executor.submit(refresh); var b = executor.submit(refresh); start.countDown();
            assertNotEquals(a.get(), b.get());
        }
    }
    @Test void refreshCredentialCannotAuthenticateAndAccessCannotRefresh() {
        SessionTokens first = sessions.issue(10001, 1, SessionKind.STUDENT);
        assertTrue(sessions.authenticate(first.refreshToken(), SessionKind.STUDENT).isEmpty());
        assertEquals(401, assertThrows(BusinessException.class, () -> sessions.refresh(first.accessToken(), SessionKind.STUDENT)).getStatus());
        assertEquals(401, assertThrows(BusinessException.class, () -> sessions.refresh(first.refreshToken(), SessionKind.ADMIN)).getStatus());
        actor = null;
        assertTrue(sessions.authenticate(first.accessToken(), SessionKind.STUDENT).isEmpty());
    }
    @Test void expiredAccessStillAllowsBoundRefreshLogoutAndRefreshExpiresAfterSevenDays() {
        SessionTokens first = sessions.issue(10001, 1, SessionKind.STUDENT);
        clock.now = clock.now.plusSeconds(900);
        sessions.revoke(first.refreshToken(), SessionKind.STUDENT);
        assertThrows(BusinessException.class, () -> sessions.refresh(first.refreshToken(), SessionKind.STUDENT));
        SessionTokens next = sessions.issue(10001, 1, SessionKind.STUDENT);
        clock.now = clock.now.plusSeconds(604800);
        assertEquals(401, assertThrows(BusinessException.class, () -> sessions.refresh(next.refreshToken(), SessionKind.STUDENT)).getStatus());
    }
    @Test void RedisUnavailableFailsClosedAndUnknownLogoutIs401() {
        SessionStore offline = new SessionStore() {
            RuntimeException offline() { return new org.springframework.dao.DataAccessResourceFailureException("offline"); }
            public Optional<SessionRecord> find(String digest, SessionKind kind) { throw offline(); }
            public void create(SessionRecord r) { throw offline(); }
            public boolean rotate(SessionRecord a, SessionRecord b) { throw offline(); }
            public void revoke(SessionRecord r) { throw offline(); }
        };
        var unavailable = new SessionTokenService(offline, (u,c,k) -> Optional.of(actor), crypto());
        assertEquals(503, assertThrows(BusinessException.class, () -> unavailable.issue(10001,1,SessionKind.STUDENT)).getStatus());
        assertEquals(503, assertThrows(BusinessException.class, () -> unavailable.authenticate("a".repeat(43),SessionKind.STUDENT)).getStatus());
        assertEquals(503, assertThrows(BusinessException.class, () -> unavailable.refresh("a".repeat(43),SessionKind.STUDENT)).getStatus());
        assertEquals(503, assertThrows(BusinessException.class, () -> unavailable.revoke("a".repeat(43),SessionKind.STUDENT)).getStatus());
        assertEquals(401, assertThrows(BusinessException.class, () -> sessions.revoke("a".repeat(43),SessionKind.STUDENT)).getStatus());
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    static class MemorySessionStore implements SessionStore {
        record Entry(SessionRecord value, Instant until) {}
        final Map<String, Entry> data = new HashMap<>(); final Clock clock;
        MemorySessionStore(Clock clock) { this.clock = clock; }
        String key(String digest, SessionKind kind) { return kind + ":" + digest; }
        public synchronized Optional<SessionRecord> find(String digest, SessionKind kind) {
            Entry entry = data.get(key(digest, kind));
            return entry != null && clock.instant().isBefore(entry.until()) ? Optional.of(entry.value()) : Optional.empty();
        }
        public synchronized void create(SessionRecord record) {
            data.put(key(record.accessDigest(), record.kind()), new Entry(record, clock.instant().plusSeconds(900)));
            data.put(key(record.refreshDigest(), record.kind()), new Entry(record, clock.instant().plusSeconds(604800)));
        }
        public synchronized boolean rotate(SessionRecord old, SessionRecord next) {
            if (find(old.refreshDigest(), old.kind()).filter(old::equals).isEmpty()) return false;
            revoke(old); create(next); return true;
        }
        public synchronized void revoke(SessionRecord old) {
            data.remove(key(old.accessDigest(), old.kind())); data.remove(key(old.refreshDigest(), old.kind()));
        }
    }
}
