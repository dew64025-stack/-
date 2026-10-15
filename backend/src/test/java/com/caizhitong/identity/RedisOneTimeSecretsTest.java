package com.caizhitong.identity;
import com.caizhitong.common.BusinessException;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class RedisOneTimeSecretsTest {
    @Test @SuppressWarnings("unchecked") void atomicNxAndGetDeleteKeepNamespacesAndOneTimeConsumption(){StringRedisTemplate redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);Map<String,String> store=new HashMap<>();when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenAnswer(call->{assertEquals(Duration.ofMinutes(5),call.getArgument(2));return store.putIfAbsent(call.getArgument(0),call.getArgument(1))==null;});when(values.getAndDelete(anyString())).thenAnswer(call->store.remove(call.getArgument(0)));var secrets=new RedisOneTimeSecrets(redis);String digest="a".repeat(64);assertTrue(secrets.claim("admin-challenge",digest,"900:1:300",Duration.ofMinutes(5)));assertFalse(secrets.claim("admin-challenge",digest,"different",Duration.ofMinutes(5)));assertNull(secrets.consume("admin-reauth",digest));assertEquals("900:1:300",secrets.consume("admin-challenge",digest));assertNull(secrets.consume("admin-challenge",digest));}
    @Test void redisFailureCannotPermitCredentials(){StringRedisTemplate redis=mock(StringRedisTemplate.class);when(redis.opsForValue()).thenThrow(new DataAccessResourceFailureException("internal-connection-secret"));var secrets=new RedisOneTimeSecrets(redis);BusinessException error=assertThrows(BusinessException.class,()->secrets.claim("wechat-code","a".repeat(64),"used",Duration.ofMinutes(5)));assertEquals(503,error.getStatus());assertFalse(error.getMessage().contains("secret"));assertNull(error.getCause());}
}
