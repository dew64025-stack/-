package com.caizhitong.common.idempotency;

import com.caizhitong.common.*;
import com.caizhitong.security.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import com.caizhitong.common.idempotency.support.MysqlDateTimeDataSource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class IdempotencyExecutorTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SensitiveDataCrypto crypto;
    @Autowired SnowflakeIds ids;
    @Autowired JsonMapper mapper;
    @Autowired Clock clock;
    IdempotencyExecutor executor;
    Actor actor = new Actor(10001, 1, Set.of("content.review"), true, true, "active", SessionKind.ADMIN);
    CurrentActor current = new CurrentActor((u,c,k) -> Optional.of(actor));
    record Result(String id, String secret) {}
    @BeforeEach void setup() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_business (id BIGINT PRIMARY KEY, secret VARCHAR(64))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS api_idempotency_records (id BIGINT PRIMARY KEY, campus_id BIGINT NOT NULL, user_id BIGINT NOT NULL, scope_digest CHAR(64) NOT NULL, key_digest CHAR(64) NOT NULL, request_digest CHAR(64) NOT NULL, response_encrypted BLOB, response_type VARCHAR(255), status VARCHAR(16) NOT NULL, created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL, UNIQUE(scope_digest,key_digest))");
        jdbc.execute("DELETE FROM test_business"); jdbc.execute("DELETE FROM api_idempotency_records");
        executor = new IdempotencyExecutor(jdbc, transactions, ids, crypto, mapper, current, clock);
        login();
    }
    void login() { SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of())); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    IdempotencyContext command(String requestDigest) { return new IdempotencyContext("POST", "/api/v1/admin/moderation-tasks/44/decision", "same-key-123", requestDigest); }
    Result create() { long id = ids.nextId(); jdbc.update("INSERT INTO test_business VALUES (?,?)", id, "private-result"); return new Result(Long.toString(id), "private-result"); }
    Result execute(String digest) { return executor.execute(command(digest), Result.class, () -> current.requirePermission("content.review"), this::create); }
    @Test void replayCreatesOneBusinessRecordAndStoresOnlyEncryptedDto() {
        Result first = execute("a".repeat(64));
        assertEquals(first, execute("a".repeat(64)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM test_business", Integer.class));
        byte[] encrypted = jdbc.queryForObject("SELECT response_encrypted FROM api_idempotency_records", byte[].class);
        assertFalse(new String(encrypted, java.nio.charset.StandardCharsets.UTF_8).contains("private-result"));
    }
    @Test void differentInputWithSameKeyIs409() {
        execute("a".repeat(64));
        assertEquals(409, assertThrows(BusinessException.class, () -> execute("b".repeat(64))).getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM test_business", Integer.class));
    }
    @Test void businessFailureRollsBackBothRecordAndAction() {
        assertThrows(IllegalStateException.class, () -> executor.execute(command("a".repeat(64)), Result.class,
                () -> current.requirePermission("content.review"), () -> { create(); throw new IllegalStateException("failed"); }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM test_business", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency_records", Integer.class));
        assertNotNull(execute("a".repeat(64)));
    }
    @Test void revokedPermissionCannotReplaySensitiveResult() {
        execute("a".repeat(64));
        actor = new Actor(10001, 1, Set.of(), true, true, "active", SessionKind.ADMIN);
        assertEquals(403, assertThrows(BusinessException.class, () -> execute("a".repeat(64))).getStatus());
    }
    @Test void concurrentCommandsSerializeAtDatabaseUniqueRecord() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Result> action = () -> { login(); start.await(); try { return execute("a".repeat(64)); } finally { clear(); } };
            var a = pool.submit(action); var b = pool.submit(action); start.countDown();
            assertEquals(a.get(), b.get());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM test_business", Integer.class));
    }
    @Test void sameKeyInDifferentCampusMethodRouteOrNamespaceDoesNotReplay() {
        Result first = execute("a".repeat(64));
        Result otherRoute = executor.execute(new IdempotencyContext("POST", "/api/v1/admin/moderation-tasks/45/decision", "same-key-123", "a".repeat(64)), Result.class, current::require, this::create);
        assertNotEquals(first.id(), otherRoute.id());
        Result otherMethod = executor.execute(new IdempotencyContext("DELETE", "/api/v1/admin/moderation-tasks/44/decision", "same-key-123", "a".repeat(64)), Result.class, current::require, this::create);
        assertNotEquals(first.id(), otherMethod.id());
        actor = new Actor(10001,2,Set.of("content.review"),true,true,"active",SessionKind.ADMIN); login();
        assertNotEquals(first.id(), execute("a".repeat(64)).id());
        actor = new Actor(10001,1,Set.of(),true,true,"active",SessionKind.STUDENT); login();
        var otherKind = executor.execute(command("a".repeat(64)), Result.class, current::require, this::create);
        assertNotEquals(first.id(), otherKind.id());
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM test_business", Integer.class));
    }
    @Test void pendingResultAndExpiredResultHaveExplicitRetryBehavior() {
        Result first = execute("a".repeat(64));
        jdbc.update("UPDATE api_idempotency_records SET status='pending'");
        BusinessException pending = assertThrows(BusinessException.class, () -> execute("a".repeat(64)));
        assertEquals(409, pending.getStatus()); assertTrue(pending.isRetryable());
        jdbc.update("UPDATE api_idempotency_records SET expires_at='2026-01-01 00:00:00'");
        assertNotEquals(first.id(), execute("b".repeat(64)).id());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency_records", Integer.class));
    }
    @Test void credentialRoutesAndGenericContainersCannotEnterDurableBusinessCache() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute(command("a".repeat(64)), Map.class, current::require, Map::of));
        assertThrows(BusinessException.class, () -> executor.execute(new IdempotencyContext("POST", "/api/v1/auth/token/refresh", "same-key-123", "a".repeat(64)), Result.class, current::require, this::create));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency_records", Integer.class));
    }
    @Test void mysqlDatetimeLocalDateTimeMappingReplaysWithoutTimestampCast() {
        var mysqlLike = new MysqlDateTimeDataSource(jdbc.getDataSource());
        var compatible = new IdempotencyExecutor(new JdbcTemplate(mysqlLike),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(mysqlLike), ids, crypto, mapper, current, clock);
        Result expected = compatible.execute(command("a".repeat(64)), Result.class, current::require, () -> new Result("10001", "secret"));
        assertEquals(expected, compatible.execute(command("a".repeat(64)), Result.class, current::require,
                () -> { throw new AssertionError("A committed result must be replayed"); }));
    }
}
