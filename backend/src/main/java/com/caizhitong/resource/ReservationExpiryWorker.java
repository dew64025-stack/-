package com.caizhitong.resource;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;

/** Releases manual pending holds even when nobody tries to reserve again. */
@Component
public class ReservationExpiryWorker {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate tx;
    public ReservationExpiryWorker(JdbcTemplate jdbc,Clock clock,PlatformTransactionManager manager){this.jdbc=jdbc;this.clock=clock;this.tx=new TransactionTemplate(manager);this.tx.setTimeout(10);}
    public void run() {
        LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        var resources=jdbc.queryForList("SELECT DISTINCT s.resource_id FROM reservations v JOIN resource_slots s ON s.id=v.slot_id WHERE v.status='pending' AND v.pending_expires_at<=? ORDER BY s.resource_id LIMIT 100",Long.class,now);
        for(long resource:resources)tx.executeWithoutResult(status->{
            jdbc.queryForList("SELECT id FROM resources WHERE id=? FOR UPDATE",Long.class,resource);
            jdbc.update("DELETE FROM resource_occupancies WHERE reservation_id IN (SELECT v.id FROM reservations v JOIN resource_slots s ON s.id=v.slot_id WHERE s.resource_id=? AND v.status='pending' AND v.pending_expires_at<=?)",resource,now);
            jdbc.update("UPDATE reservations SET status='cancelled',version=version+1,updated_at=?,pending_expires_at=NULL WHERE status='pending' AND pending_expires_at<=? AND slot_id IN (SELECT id FROM resource_slots WHERE resource_id=?)",now,now,resource);
        });
    }
}
