package com.caizhitong.privacy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Data export/deletion polling; disabled in isolated tests. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.privacy.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class DataRightsScheduling {
    private final DataRightsWorker worker;

    public DataRightsScheduling(DataRightsWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.privacy.poll-ms:5000}")
    public void run() {
        worker.run();
    }
}
