package com.caizhitong.common.idempotency;
import com.caizhitong.common.*;
import com.caizhitong.security.CurrentActor;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import com.caizhitong.security.Actor;
import com.caizhitong.security.RoutePolicy;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Collection;
import java.util.Map;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class IdempotencyExecutor {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SnowflakeIds ids;
    private final SensitiveDataCrypto crypto;
    private final JsonMapper mapper;
    private final CurrentActor current;
    private final Clock clock;
    public IdempotencyExecutor(JdbcTemplate jdbc, PlatformTransactionManager tx, SnowflakeIds ids,
            SensitiveDataCrypto crypto, JsonMapper mapper, CurrentActor current, Clock clock) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(tx); this.ids = ids;
        this.crypto = crypto; this.mapper = mapper; this.current = current; this.clock = clock;
        transaction.setTimeout(10);
    }
    public <T> T execute(IdempotencyContext context, Class<T> type, Runnable authorize, Supplier<T> action) {
        if (context == null || type == null || authorize == null || action == null) throw new IllegalArgumentException("Business write context is required");
        if (RoutePolicy.credentialException(context.method(), context.route()))
            throw new BusinessException(400, "INVALID_BUSINESS_WRITE", "凭据操作不能进入业务幂等缓存");
        if (type.isPrimitive() || type.isArray() || type == Object.class || type == com.caizhitong.security.SessionTokens.class
                || ApiResponse.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type))
            throw new IllegalArgumentException("Business write must return a concrete business DTO");
        try {
            return transaction.execute(status -> {
                // This executes before both reservation and replay, inside the business transaction.
                authorize.run();
                Actor actor = RoutePolicy.ownRights(context.method(), context.route()) ? current.requireAuthenticatedIdentity() : current.require();
                String scope = digest("idempotency-scope", actor.kind() + ":" + actor.userId() + ":" + actor.campusId()
                        + ":" + context.method() + ":" + context.route());
                String key = digest("idempotency-key", context.key());
                long id = ids.nextId();
                var now = clock.instant();
                LocalDateTime created = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
                LocalDateTime expires = LocalDateTime.ofInstant(now.plus(Duration.ofDays(1)), ZoneOffset.UTC);
                jdbc.update("""
                        INSERT INTO api_idempotency_records
                        (id,campus_id,user_id,scope_digest,key_digest,request_digest,status,created_at,expires_at)
                        VALUES (?,?,?,?,?,?,'pending',?,?) ON DUPLICATE KEY UPDATE id=id
                        """, id, actor.campusId(), actor.userId(), scope, key, context.requestDigest(),
                        created, expires);
                StoredRecord row = jdbc.queryForObject("""
                        SELECT id,request_digest,status,response_type,expires_at,response_encrypted
                        FROM api_idempotency_records WHERE scope_digest=? AND key_digest=? FOR UPDATE
                        """, (rows, index) -> new StoredRecord(rows.getLong("id"), rows.getString("request_digest"), rows.getString("status"),
                                rows.getString("response_type"), rows.getObject("expires_at", LocalDateTime.class), rows.getBytes("response_encrypted")), scope, key);
                long savedId = row.id();
                if (savedId != id && !row.expiresAt().toInstant(ZoneOffset.UTC).isAfter(now)) {
                    jdbc.update("DELETE FROM api_idempotency_records WHERE id=?", savedId);
                    jdbc.update("""
                            INSERT INTO api_idempotency_records
                            (id,campus_id,user_id,scope_digest,key_digest,request_digest,status,created_at,expires_at)
                            VALUES (?,?,?,?,?,?,'pending',?,?)
                            """, id, actor.campusId(), actor.userId(), scope, key, context.requestDigest(),
                            created, expires);
                    savedId = id;
                }
                String purpose = "idempotency-result:" + scope + ":" + key + ":" + context.requestDigest();
                if (savedId != id) {
                    if (!context.requestDigest().equals(row.requestDigest())) throw conflict("IDEMPOTENCY_CONFLICT", false);
                    if (!"complete".equals(row.status())) throw conflict("IDEMPOTENCY_PENDING", true);
                    if (!type.getName().equals(row.responseType())) throw conflict("IDEMPOTENCY_RESULT_TYPE", false);
                    return mapper.readValue(crypto.decrypt(purpose, row.encrypted()), type);
                }
                T result = action.get();
                byte[] encrypted = crypto.encrypt(purpose, mapper.writeValueAsString(result));
                jdbc.update("UPDATE api_idempotency_records SET response_encrypted=?,response_type=?,status='complete' WHERE id=?",
                        encrypted, type.getName(), id);
                return result;
            });
        } catch (TransientDataAccessException e) {
            // TransactionTemplate has already rolled back; never commit after a database error.
            throw new BusinessException(503, "IDEMPOTENCY_BUSY", "业务正在处理中，请稍后重试", null, true);
        }
    }
    private String digest(String purpose, String value) { return HexFormat.of().formatHex(crypto.searchHash(purpose, value)); }
    private BusinessException conflict(String code, boolean retryable) { return new BusinessException(409, code, "请求与已有业务操作冲突", null, retryable); }
    private record StoredRecord(long id, String requestDigest, String status, String responseType, LocalDateTime expiresAt, byte[] encrypted) {}
}
