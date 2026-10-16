package com.caizhitong.service;

import com.caizhitong.resource.ReservationExpiryWorker;
import com.caizhitong.security.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Explicit opt-in: exclusively owned fresh database on the labelled ABC review container. */
@EnabledIfEnvironmentVariable(named="CZT_ABC_B_MYSQL_TEST",matches="true")
@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("test")
@Import(ServiceHttpTest.Fixtures.class)
class MysqlServiceRegressionTest {
    private static String url,password;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties)throws Exception {
        url=System.getenv("CZT_ABC_B_MYSQL_URL");password=System.getenv("CZT_ABC_B_MYSQL_PASSWORD");
        String port=System.getenv("CZT_ABC_B_VERIFIED_PORT");
        if(port==null||!port.matches("[0-9]{1,5}")||Set.of("3306","13306").contains(port)||url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:"+port+"/czt_abc_b_[a-z0-9_]+\\?.*")||password==null||password.isBlank()
                ||!"abc-review-repairs-20261015".equals(System.getenv("CZT_ABC_B_VERIFIED_LABEL")))throw new IllegalStateException("Fresh isolated ABC B database and verified container label required");
        String schema=url.substring(url.lastIndexOf('/')+1,url.indexOf('?'));
        try(var connection=DriverManager.getConnection(url.replace("/"+schema+"?","/?"),"root",password);var statement=connection.createStatement()){
            statement.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        Flyway.configure().dataSource(url,"root",password).locations("filesystem:"+Path.of("..","sql").toAbsolutePath()).target(MigrationVersion.fromVersion("16")).cleanDisabled(true).load().migrate();
        try(var connection=DriverManager.getConnection(url,"root",password);var statement=connection.createStatement()){
            statement.execute("INSERT INTO campuses(id,name,code,created_at,updated_at) VALUES(1,'Synthetic B','b-test',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
            for(int user:new int[]{101,102})statement.execute("INSERT INTO users(id,campus_id,status,created_at,updated_at) VALUES("+user+",1,'active',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
            statement.execute("INSERT INTO resources(id,campus_id,type,name,capacity,status,source_type,created_at,updated_at) VALUES(2001,1,'library_seat','Legacy exclusive',5,'active','demo',UTC_TIMESTAMP(),UTC_TIMESTAMP()),(2002,1,'library_seat','Mixed legacy',5,'active','demo',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
            statement.execute("INSERT INTO resource_slots(id,resource_id,slot_date,start_time,end_time,capacity,status,created_at,updated_at) VALUES(2101,2001,'2026-10-20','08:00','10:00',NULL,'available',UTC_TIMESTAMP(),UTC_TIMESTAMP()),(2102,2002,'2026-10-20','08:00','10:00',NULL,'available',UTC_TIMESTAMP(),UTC_TIMESTAMP()),(2103,2002,'2026-10-21','08:00','10:00',3,'available',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
            statement.execute("INSERT INTO reservations(id,campus_id,user_id,slot_id,seat_no,status,source_type,created_at,updated_at) VALUES(2201,1,101,2101,0,'confirmed','demo',UTC_TIMESTAMP(),UTC_TIMESTAMP()),(2202,1,101,2102,0,'confirmed','demo',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
            statement.execute("INSERT INTO resource_occupancies(id,slot_id,reservation_id,seat_no,created_at,updated_at) VALUES(2301,2101,2201,0,UTC_TIMESTAMP(),UTC_TIMESTAMP()),(2302,2102,2202,0,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        }
        Flyway.configure().dataSource(url,"root",password).locations("filesystem:"+Path.of("..","sql").toAbsolutePath()).target(MigrationVersion.fromVersion("17")).cleanDisabled(true).load().migrate();
        properties.add("spring.datasource.url",()->url);properties.add("spring.datasource.username",()->"root");properties.add("spring.datasource.password",()->password);
        properties.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");properties.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone = '+00:00'");properties.add("spring.jpa.hibernate.ddl-auto",()->"none");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired SessionTokenService sessions;
    @Autowired ReservationExpiryWorker expiry;
    @Autowired ServiceHttpTest.TestClock clock;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.caizhitong.common.SnowflakeIds ids;
    @Autowired com.caizhitong.resource.ServiceSources sources;
    private final JsonMapper mapper=new JsonMapper();
    private String first,second;
    @BeforeEach void actors(){
        clock.value=Instant.parse("2026-10-20T00:00:00Z");
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("czt_abc_b_"));
        assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
        for(int user:new int[]{101,102}){
            if(jdbc.queryForObject("SELECT COUNT(*) FROM user_phones WHERE user_id=?",Integer.class,user)==0){
                jdbc.update("INSERT INTO user_phones(id,user_id,phone_encrypted,phone_search,verified,created_at) VALUES(?,?,?, ?,1,UTC_TIMESTAMP())",user,user,new byte[]{1},"synthetic-phone-"+user);
                jdbc.update("INSERT INTO authorizations(id,user_id,type,scope,status,granted_at,created_at) VALUES(?,?,'phone','*','granted',UTC_TIMESTAMP(),UTC_TIMESTAMP())",user+1000,user);
                jdbc.update("INSERT INTO student_verifications(id,user_id,campus_id,student_id_encrypted,student_id_search,status,created_at,updated_at) VALUES(?,?,1,?,?,'verified',UTC_TIMESTAMP(),UTC_TIMESTAMP())",user+3000,user,new byte[]{1},"synthetic-student-"+user);
            }
        }
        first="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();second="Bearer "+sessions.issue(102,1,SessionKind.STUDENT).accessToken();
    }
    @Test void legacyNullSlotCapacityPreservesOccupiedExclusiveMode()throws Exception {
        assertEquals("exclusive",jdbc.queryForObject("SELECT reservation_mode FROM resources WHERE id=2001",String.class));
        command(first,2101,1,"legacy-wrong-seat").andExpect(status().isConflict());
        command(second,2101,0,"legacy-occupied-seat").andExpect(status().isConflict());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE reservation_id=2201 AND seat_no=0",Integer.class));
    }
    @Test void mixedLegacyModesBlockBookingWithExplicitConfigurationError()throws Exception {
        command(second,2102,1,"mixed-blocked").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RESERVATION_CONFIGURATION_INVALID"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE reservation_id=2202 AND seat_no=0",Integer.class));
    }
    @Test void unsignedMapsReadOwnerReservationWithRealConnector()throws Exception {
        assertInstanceOf(java.math.BigInteger.class,jdbc.queryForMap("SELECT user_id FROM reservations WHERE id=2201").get("user_id"));
        mvc.perform(get("/api/v1/reservations/2201").header("Authorization",first)).andExpect(status().isOk()).andExpect(jsonPath("$.data.slot.id").value("2101"));
    }
    @Test void realMysqlFinalSeatIsUniqueAndWorkerReleasesExpiredHold()throws Exception {
        jdbc.update("INSERT INTO resources(id,campus_id,type,name,capacity,reservation_mode,status,source_type,created_at,updated_at) VALUES(2401,1,'library_seat','Concurrent seat',1,'capacity','active','manual',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO resource_slots(id,resource_id,slot_date,start_time,end_time,capacity,status,created_at,updated_at) VALUES(2402,2401,'2026-10-20','08:00','10:00',1,'available',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{ready.countDown();go.await();return command(first,2402,1,"mysql-race-a").andReturn().getResponse().getStatus();});
            var b=pool.submit(()->{ready.countDown();go.await();return command(second,2402,1,"mysql-race-b").andReturn().getResponse().getStatus();});
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();var statuses=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertEquals(1,statuses.stream().filter(s->s==201).count());assertEquals(1,statuses.stream().filter(s->s==409).count());
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE slot_id=2402",Integer.class));
        clock.value=clock.value.plusSeconds(901);expiry.run();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE slot_id=2402",Integer.class));
        assertEquals("cancelled",jdbc.queryForObject("SELECT status FROM reservations WHERE slot_id=2402",String.class));
    }
    @Test void concurrentVotesCountBothAfterWaitingForIssueLock()throws Exception {
        jdbc.update("INSERT INTO campus_issues(id,campus_id,user_id,title,category,status,visibility,source_type,created_at,updated_at) VALUES(2501,1,101,'Concurrent voting','other','published','public','manual',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->transactionalVote(101,ready,go));var b=pool.submit(()->transactionalVote(102,ready,go));
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        assertEquals(2,jdbc.queryForObject("SELECT vote_count FROM campus_issues WHERE id=2501",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM campus_issue_votes WHERE issue_id=2501",Integer.class));
    }
    @Test void moderationCommittedAfterSnapshotStillBlocksPrivateIssueInteraction(){
        jdbc.update("INSERT INTO campus_issues(id,campus_id,user_id,title,category,status,visibility,source_type,created_at,updated_at) VALUES(2502,1,101,'Newly private issue','other','published','public','manual',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        var outer=new org.springframework.transaction.support.TransactionTemplate(transactions);
        outer.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT visibility FROM campus_issues WHERE id=2502",String.class);
            var moderation=new org.springframework.transaction.support.TransactionTemplate(transactions);moderation.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            moderation.executeWithoutResult(inner->jdbc.update("UPDATE campus_issues SET visibility='private',status='hidden' WHERE id=2502"));
            var error=assertThrows(com.caizhitong.common.BusinessException.class,()->issueService(102).vote(2502,new com.caizhitong.issue.IssueInputs.Vote(mapper.readTree("1"))));
            assertEquals(404,error.getStatus());
        });
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM campus_issue_votes WHERE issue_id=2502",Integer.class));
    }
    @Test void approvalCannotUseActiveResourceFromEarlierRepeatableReadSnapshot(){
        assertApprovalUsesCurrentState(2601,2602,2603,true);
    }
    @Test void approvalCannotUseAvailableSlotFromEarlierRepeatableReadSnapshot(){
        assertApprovalUsesCurrentState(2611,2612,2613,false);
    }
    @Test void legacyOverlappingExclusiveAvailabilityMatchesWriteConflict()throws Exception {
        jdbc.update("INSERT INTO resource_slots(id,resource_id,slot_date,start_time,end_time,capacity,status,created_at,updated_at) VALUES(2104,2001,'2026-10-20','09:00','11:00',NULL,'available',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        mvc.perform(get("/api/v1/resource-slots/2104/availability").header("Authorization",second)).andExpect(status().isOk()).andExpect(jsonPath("$.data.canReserve").value(false)).andExpect(jsonPath("$.data.slot.remaining").value(0)).andExpect(jsonPath("$.data.availableSeatNos.length()").value(0));
        command(second,2104,0,"mysql-overlap-exclusive").andExpect(status().isConflict());
    }
    private void assertApprovalUsesCurrentState(long resource,long slot,long reservation,boolean stopResource){
        jdbc.update("INSERT INTO resources(id,campus_id,type,name,capacity,reservation_mode,status,source_type,created_at,updated_at) VALUES(?,1,'library_seat','Approval snapshot fixture',1,'capacity','active','manual',UTC_TIMESTAMP(),UTC_TIMESTAMP())",resource);
        jdbc.update("INSERT INTO resource_slots(id,resource_id,slot_date,start_time,end_time,capacity,status,created_at,updated_at) VALUES(?,?,'2026-10-20','08:00','10:00',1,'available',UTC_TIMESTAMP(),UTC_TIMESTAMP())",slot,resource);
        jdbc.update("INSERT INTO reservations(id,campus_id,user_id,slot_id,seat_no,status,source_type,pending_expires_at,created_at,updated_at) VALUES(?,1,101,?,1,'pending','manual','2026-10-20 00:15:00',UTC_TIMESTAMP(),UTC_TIMESTAMP())",reservation,slot);
        jdbc.update("INSERT INTO resource_occupancies(id,slot_id,reservation_id,seat_no,created_at,updated_at) VALUES(?,?,?,1,UTC_TIMESTAMP(),UTC_TIMESTAMP())",reservation+100,slot,reservation);
        var actor=new Actor(900,1,Set.of("resource.process"),true,true,"active",SessionKind.ADMIN);var current=org.mockito.Mockito.mock(CurrentActor.class);
        org.mockito.Mockito.when(current.requirePermission("resource.process")).thenReturn(actor);
        var service=new com.caizhitong.resource.ResourceService(jdbc,current,ids,clock,null,null,null,sources,mapper);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            assertEquals("active",jdbc.queryForObject("SELECT status FROM resources WHERE id=?",String.class,resource));
            assertEquals("available",jdbc.queryForObject("SELECT status FROM resource_slots WHERE id=?",String.class,slot));
            var disabling=new org.springframework.transaction.support.TransactionTemplate(transactions);disabling.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            disabling.executeWithoutResult(inner->{if(stopResource)jdbc.update("UPDATE resources SET status='inactive' WHERE id=?",resource);else jdbc.update("UPDATE resource_slots SET status='maintenance' WHERE id=?",slot);});
            var input=new com.caizhitong.resource.ResourceInputs.Decision(mapper.readTree("\"approve\""),mapper.readTree("0"),mapper.readTree("\"Human approval\""));
            var error=assertThrows(com.caizhitong.common.BusinessException.class,()->service.decide(reservation,input));
            assertEquals(stopResource?404:409,error.getStatus());
        });
        assertEquals("pending",jdbc.queryForObject("SELECT status FROM reservations WHERE id=?",String.class,reservation));
    }
    private int transactionalVote(long user,CountDownLatch ready,CountDownLatch go){
        var service=issueService(user);
        return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
            jdbc.queryForObject("SELECT COUNT(*) FROM campus_issue_votes WHERE issue_id=2501",Integer.class);ready.countDown();
            try{if(!go.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Snapshot barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            return service.vote(2501,new com.caizhitong.issue.IssueInputs.Vote(mapper.readTree("-1"))).voteCount();
        });
    }
    private com.caizhitong.issue.IssueService issueService(long user){
        // Mock only the already authenticated identity. The service, JDBC, locks and transactions are real.
        var actor=new Actor(user,1,Set.of(),true,true,"active",SessionKind.STUDENT);var current=org.mockito.Mockito.mock(CurrentActor.class);
        org.mockito.Mockito.when(current.requireStudent()).thenReturn(actor);org.mockito.Mockito.when(current.require()).thenReturn(actor);org.mockito.Mockito.when(current.optional()).thenReturn(Optional.of(actor));
        return new com.caizhitong.issue.IssueService(jdbc,current,ids,clock,mapper,null,null,null,sources,100);
    }
    private ResultActions command(String bearer,long slot,int seat,String key)throws Exception{return mvc.perform(post("/api/v1/reservations").header("Authorization",bearer).header("Idempotency-Key",key).contentType("application/json").content("{\"slotId\":\""+slot+"\",\"seatNo\":"+seat+"}"));}
}
