package com.caizhitong.material.files;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
@Configuration @EnableScheduling
@ConditionalOnProperty(name="app.files.scheduling-enabled",havingValue="true",matchIfMissing=true)
public class FileScheduling {
    private final FileScanWorker scan;
    private final FileCleanupWorker cleanup;
    public FileScheduling(FileScanWorker scan,FileCleanupWorker cleanup){this.scan=scan;this.cleanup=cleanup;}
    @Scheduled(fixedDelayString="${app.files.poll-ms:5000}") public void scan(){scan.run();}
    @Scheduled(fixedDelayString="${app.files.poll-ms:5000}") public void cleanup(){cleanup.run();}
}
