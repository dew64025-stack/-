package com.caizhitong.identity;
import com.caizhitong.common.BusinessException;
import java.time.Duration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
@Component public class RedisOneTimeSecrets implements OneTimeSecrets {
    private final StringRedisTemplate redis;
    public RedisOneTimeSecrets(StringRedisTemplate redis){this.redis=redis;}
    public boolean claim(String namespace,String digest,String value,Duration ttl){try{return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(namespace,digest),value,ttl));}catch(DataAccessException e){throw unavailable();}}
    public String consume(String namespace,String digest){try{return redis.opsForValue().getAndDelete(key(namespace,digest));}catch(DataAccessException e){throw unavailable();}}
    private String key(String namespace,String digest){if(!namespace.matches("[a-z-]{1,32}")||!digest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid secret scope");return "identity:v1:"+namespace+":"+digest;}
    private BusinessException unavailable(){return new BusinessException(503,"IDENTITY_STORE_UNAVAILABLE","身份凭据服务暂时不可用",null,true);}
}
