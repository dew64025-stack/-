package com.caizhitong.admin.identity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
@Configuration @EnableScheduling
@ConditionalOnProperty(name="app.identity.roster-scheduling-enabled",havingValue="true",matchIfMissing=true)
public class RosterScheduling {
    private final StudentRosterWorker worker;public RosterScheduling(StudentRosterWorker worker){this.worker=worker;}
    @Scheduled(fixedDelayString="${app.identity.roster-poll-ms:5000}") public void poll(){worker.run();}
}
