package com.caizhitong.security;

import com.caizhitong.common.BusinessException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RedisSessionStoreTest {
    @Test void atomicRedisCompareMissMustReachServiceAs401() {
        SessionTokenService service = serviceWithRevokeReply(0L);
        assertEquals(401, assertThrows(BusinessException.class, () -> service.revoke("a".repeat(43), SessionKind.STUDENT)).getStatus());
    }
    @Test void atomicRedisMatchAllowsLogoutAndMissingReplyIs503() {
        assertDoesNotThrow(() -> serviceWithRevokeReply(1L).revoke("a".repeat(43), SessionKind.STUDENT));
        assertEquals(503, assertThrows(BusinessException.class,
                () -> serviceWithRevokeReply(null).revoke("a".repeat(43), SessionKind.STUDENT)).getStatus());
    }
    @SuppressWarnings("unchecked")
    private SessionTokenService serviceWithRevokeReply(Long reply) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        var crypto = OpaqueSessionTest.crypto();
        String digest = HexFormat.of().formatHex(crypto.searchHash("session:STUDENT", "a".repeat(43)));
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("session:{STUDENT}:" + digest)).thenReturn("10001|1|STUDENT|session|" + digest + "|" + "b".repeat(64));
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class))).thenReturn(reply);
        Actor actor = new Actor(10001,1,Set.of(),true,true,"active",SessionKind.STUDENT);
        return new SessionTokenService(new RedisSessionStore(redis), (u,c,k) -> Optional.of(actor), crypto);
    }
}
