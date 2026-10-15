package com.caizhitong.integration.storage;

import com.caizhitong.material.files.FilePurpose;
import io.minio.*;
import io.minio.Http.Method;
import java.io.InputStream;
import java.time.Duration;

public final class S3ObjectStorage implements ObjectStorage {
    private final MinioClient client;
    private final String bucket;
    public S3ObjectStorage(MinioClient client,String bucket){this.client=client;this.bucket=bucket;}
    private MinioClient configured(){if(client==null||bucket==null||bucket.isBlank())throw FilePurpose.unavailable();return client;}
    public void put(String key,InputStream stream,String mime){
        try{configured().putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(mime).stream(stream,-1L,5L*1024*1024).build());}
        catch(Exception e){throw FilePurpose.unavailable();}
    }
    public InputStream open(String key){
        try{return configured().getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());}
        catch(Exception e){throw FilePurpose.unavailable();}
    }
    public void delete(String key){
        try{configured().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());}
        catch(Exception e){throw FilePurpose.unavailable();}
    }
    public String sign(String key,Duration ttl){
        if(ttl.getSeconds()!=120)throw new IllegalArgumentException("Private downloads require 120 seconds");
        try{return configured().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().bucket(bucket).object(key).method(Method.GET).expiry(120).build());}
        catch(Exception e){throw FilePurpose.unavailable();}
    }
}
