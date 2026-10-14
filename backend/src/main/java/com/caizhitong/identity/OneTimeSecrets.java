package com.caizhitong.identity;
import java.time.Duration;
public interface OneTimeSecrets {
    boolean claim(String namespace,String digest,String value,Duration ttl);
    String consume(String namespace,String digest);
}
