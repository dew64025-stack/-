package com.caizhitong.integration.storage;

import java.io.InputStream;
import java.time.Duration;

/** Server-generated opaque keys only. No controller or caller-provided URL. */
public interface ObjectStorage {
    void put(String key,InputStream stream,String mime);
    InputStream open(String key);
    void delete(String key);
    String sign(String key,Duration ttl);
}
