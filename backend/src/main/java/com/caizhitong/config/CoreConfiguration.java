package com.caizhitong.config;

import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.common.SnowflakeIds;
import java.time.Clock;
import java.util.Base64;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
public class CoreConfiguration {
    @Bean public Clock clock() { return Clock.systemUTC(); }

    @Bean public SnowflakeIds snowflakeIds(Clock clock, @Value("${app.ids.worker-id}") int workerId) {
        return new SnowflakeIds(clock, workerId);
    }

    @Bean public SensitiveDataCrypto sensitiveDataCrypto(
            @Value("${app.crypto.encryption-key}") String encryptionKey,
            @Value("${app.crypto.hmac-key}") String hmacKey) {
        try {
            return new SensitiveDataCrypto(Base64.getDecoder().decode(encryptionKey), Base64.getDecoder().decode(hmacKey));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid encryption or HMAC key configuration");
        }
    }

    @Bean public static BeanFactoryPostProcessor databaseGuard(Environment environment) {
        return beanFactory -> {
            if (environment.acceptsProfiles(Profiles.of("test"))) return;
            String url = environment.getProperty("spring.datasource.url", "");
            String appUser = environment.getProperty("spring.datasource.username", "");
            String migrator = environment.getProperty("spring.flyway.user", "");
            if (!url.startsWith("jdbc:mysql:")) throw new IllegalStateException("Production requires MySQL");
            if (appUser.isBlank() || migrator.isBlank() || appUser.equals(migrator)) {
                throw new IllegalStateException("Separate application and Flyway accounts are required");
            }
        };
    }
}
