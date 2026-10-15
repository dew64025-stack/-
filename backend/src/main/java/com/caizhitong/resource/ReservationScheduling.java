package com.caizhitong.resource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration @EnableScheduling @Profile("!test")
@ConditionalOnProperty(name="app.resources.scheduling-enabled",havingValue="true",matchIfMissing=true)
public class ReservationScheduling {
    private final ReservationExpiryWorker worker;
    public ReservationScheduling(ReservationExpiryWorker worker){this.worker=worker;}
    @Scheduled(fixedDelayString="${app.resources.expiry-poll-ms:30000}") public void poll(){worker.run();}
}
