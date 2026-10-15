package com.caizhitong.integration.storage;

import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.net.URI;
import java.time.Duration;

@Configuration
public class StorageConfiguration {
    @Bean public ObjectStorage objectStorage(@Value("${app.files.s3.endpoint:}") String endpoint,
            @Value("${app.files.s3.access-key:}") String access,@Value("${app.files.s3.secret-key:}") String secret,
            @Value("${app.files.s3.bucket:}") String bucket,@Value("${app.files.s3.region:us-east-1}")String region) {
        if(endpoint.isBlank()||access.isBlank()||secret.isBlank()||bucket.isBlank())return new S3ObjectStorage(null,null);
        URI uri=URI.create(endpoint);
        if(!java.util.Set.of("http","https").contains(uri.getScheme())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getHost()==null)throw new IllegalArgumentException("Invalid S3 endpoint");
        var http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofSeconds(30)).writeTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(60)).build();
        return new S3ObjectStorage(MinioClient.builder().endpoint(endpoint).region(region).credentials(access,secret).httpClient(http).build(),bucket);
    }
}
