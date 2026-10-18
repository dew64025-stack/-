package com.caizhitong.admin.operations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Integration test/sync polling; disabled in isolated tests. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.integrations.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class IntegrationScheduling {
    private final IntegrationWorker worker;

    public IntegrationScheduling(IntegrationWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.integrations.poll-ms:5000}")
    public void run() {
        worker.run();
    }
}
