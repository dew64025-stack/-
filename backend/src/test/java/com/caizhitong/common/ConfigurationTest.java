package com.caizhitong.common;

import com.caizhitong.config.CoreConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @Test void invalidDatabaseConfigIsRejectedBeforeMigrationCanStart() {
        var migrationStarted = new AtomicBoolean();
        new ApplicationContextRunner()
                .withBean("migration", InitializingBean.class, () -> () -> migrationStarted.set(true))
                .withUserConfiguration(CoreConfiguration.class)
                .withPropertyValues("spring.datasource.url=jdbc:mysql://localhost/caizhitong",
                        "spring.datasource.username=shared", "spring.flyway.user=shared",
                        "app.ids.worker-id=0", "app.crypto.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                        "app.crypto.hmac-key=AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertFalse(migrationStarted.get(), "Must reject shared credentials before initialization side effects");
                });
    }

    @Test void secretsAreRequiredAndInvalidBase64DoesNotLeakInput() {
        var config = new CoreConfiguration();
        var error = assertThrows(IllegalArgumentException.class, () -> config.sensitiveDataCrypto("secret-do-not-leak", ""));
        assertFalse(error.getMessage().contains("secret-do-not-leak"));
        byte[] hmac = new byte[32];
        hmac[0] = 1;
        assertNotNull(config.sensitiveDataCrypto(Base64.getEncoder().encodeToString(new byte[32]), Base64.getEncoder().encodeToString(hmac)));
    }

    @Test void productionRejectsH2AndSharedMigrationAccount() throws Exception {
        var environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:h2:mem:unsafe")
                .withProperty("spring.datasource.username", "app")
                .withProperty("spring.flyway.user", "migrator");
        assertThrows(IllegalStateException.class, () -> CoreConfiguration.databaseGuard(environment).postProcessBeanFactory(new DefaultListableBeanFactory()));
        environment.setProperty("spring.datasource.url", "jdbc:mysql://localhost/caizhitong");
        environment.setProperty("spring.flyway.user", "app");
        assertThrows(IllegalStateException.class, () -> CoreConfiguration.databaseGuard(environment).postProcessBeanFactory(new DefaultListableBeanFactory()));
        environment.setProperty("spring.flyway.user", "migrator");
        CoreConfiguration.databaseGuard(environment).postProcessBeanFactory(new DefaultListableBeanFactory());
    }
}
