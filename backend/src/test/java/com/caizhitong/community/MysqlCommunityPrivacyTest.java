package com.caizhitong.community;

import com.caizhitong.security.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Fresh synthetic schema only, with independent schema migrator/application/investigation accounts. */
@EnabledIfEnvironmentVariable(named="CZT_ABC_C_MYSQL_TEST",matches="true")
@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("test")
@Import(CommunityHttpTest.Fixtures.class)
class MysqlCommunityPrivacyTest {
    static String url,schema,app,migrator,investigator,appPassword,investigatorPassword;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties)throws Exception {
        String port=System.getenv("CZT_ABC_C_VERIFIED_PORT");
        schema=System.getenv("CZT_ABC_C_SCHEMA");
        String rootPassword=System.getenv("CZT_ABC_C_ROOT_PASSWORD");
        if(port==null||!port.matches("[0-9]{1,5}")||Set.of("3306","13306").contains(port)||schema==null||!schema.matches("czt_abc_c_[a-z0-9_]+")||rootPassword==null||rootPassword.isBlank()
                ||!"abc-review-repairs-20261015".equals(System.getenv("CZT_ABC_C_VERIFIED_LABEL")))throw new IllegalStateException("Owned container and unique synthetic C schema required");
        String server="jdbc:mysql://127.0.0.1:"+port+"/";
        String options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        url=server+schema+options;
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
        app="czt_c_a_"+suffix;migrator="czt_c_m_"+suffix;investigator="czt_c_i_"+suffix;
        appPassword=UUID.randomUUID().toString();investigatorPassword=UUID.randomUUID().toString();
        String migrationPassword=UUID.randomUUID().toString();
        try(var root=DriverManager.getConnection(server+options,"root",rootPassword);var statement=root.createStatement()){
            statement.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            for(var pair:List.of(new String[]{app,appPassword},new String[]{migrator,migrationPassword},new String[]{investigator,investigatorPassword}))
                statement.execute("CREATE USER '"+pair[0]+"'@'%' IDENTIFIED BY '"+pair[1]+"'");
            statement.execute("GRANT CREATE,ALTER,DROP,INDEX,REFERENCES,CREATE TEMPORARY TABLES,CREATE ROUTINE,ALTER ROUTINE,EXECUTE,SELECT,INSERT,UPDATE,DELETE ON "+schema+".* TO '"+migrator+"'@'%'");
            Flyway.configure().dataSource(url,migrator,migrationPassword).locations("filesystem:"+Path.of("..","sql").toAbsolutePath()).cleanDisabled(true).load().migrate();
            // Apply the documented app grants verbatim with only isolated schema/account substitution.
            for(String line:Files.readAllLines(Path.of("..","DATABASE.md")))if(line.startsWith("GRANT ")&&line.contains("TO 'caizhitong_app'@'<ip>';"))
                statement.execute(line.replace("caizhitong.",schema+".").replace("'caizhitong_app'@'<ip>'","'"+app+"'@'%'"));
            statement.execute("GRANT SELECT (post_id,comment_id,user_id_encrypted) ON "+schema+".pseudo_identities TO '"+investigator+"'@'%'");
            statement.execute("GRANT SELECT (id,campus_id) ON "+schema+".community_posts TO '"+investigator+"'@'%'");
            statement.execute("GRANT SELECT (id,post_id) ON "+schema+".community_comments TO '"+investigator+"'@'%'");
            statement.execute("GRANT SELECT,INSERT ON "+schema+".security_investigations TO '"+investigator+"'@'%'");
            statement.execute("REVOKE CREATE,ALTER,DROP,INDEX,REFERENCES,CREATE TEMPORARY TABLES,CREATE ROUTINE,ALTER ROUTINE,EXECUTE,SELECT,INSERT,UPDATE,DELETE ON "+schema+".* FROM '"+migrator+"'@'%'");
            for(String table:List.of("pseudo_identities","community_posts","community_comments"))statement.execute("GRANT SELECT ON "+schema+"."+table+" TO '"+migrator+"'@'%'");
            for(String routine:List.of("community_owner_check","community_owner_posts"))statement.execute("GRANT EXECUTE ON PROCEDURE "+schema+"."+routine+" TO '"+migrator+"'@'%'");
            try(var rows=statement.executeQuery("SELECT DEFINER,SECURITY_TYPE FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA='"+schema+"'")){
                int count=0;while(rows.next()){assertEquals(migrator+"@%",rows.getString(1));assertEquals("DEFINER",rows.getString(2));count++;}assertEquals(2,count);
            }
        } catch(SQLException failure){throw new IllegalStateException("Isolated provisioning failed: SQLState="+failure.getSQLState()+", code="+failure.getErrorCode());}
        finally {rootPassword=null;migrationPassword=null;}
        properties.add("spring.datasource.url",()->url);properties.add("spring.datasource.username",()->app);properties.add("spring.datasource.password",()->appPassword);
        properties.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");properties.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone = '+00:00'");properties.add("spring.jpa.hibernate.ddl-auto",()->"none");
        properties.add("app.security.investigation.url",()->url);properties.add("app.security.investigation.username",()->investigator);properties.add("app.security.investigation.password",()->investigatorPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired SessionTokenService sessions;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.caizhitong.common.SensitiveDataCrypto crypto;
    @Autowired com.caizhitong.common.SnowflakeIds ids;
    @Autowired com.caizhitong.identity.OneTimeSecrets secrets;
    @Autowired AnonymousOwnership ownership;
    final JsonMapper mapper=new JsonMapper();

    @Test void minimumAppFileMetadataSupportsUploadScanCleanupAndImmutableVersions()throws Exception {
        long upload=ids.nextId(),attachment=ids.nextId(),cleanup=ids.nextId(),file=ids.nextId(),version=ids.nextId();
        String hash="a".repeat(64);
        jdbc.update("INSERT INTO file_uploads(id,campus_id,owner_user_id,access_domain,purpose,file_name,mime_type,size_bytes,token_digest,expires_at,object_key_encrypted,created_at,updated_at) VALUES(?,1,101,'student','material','Synthetic.pdf','application/pdf',1,?,UTC_TIMESTAMP()+INTERVAL 10 MINUTE,?,UTC_TIMESTAMP(),UTC_TIMESTAMP())",upload,hash,new byte[]{1});
        jdbc.update("INSERT INTO file_cleanup_jobs(id,object_key_encrypted,upload_id,next_retry_at,created_at,updated_at) VALUES(?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP())",cleanup,new byte[]{1},upload);
        jdbc.update("INSERT INTO file_attachments(id,upload_id,campus_id,owner_user_id,access_domain,purpose,file_name,mime_type,size_bytes,original_sha256,object_key_encrypted,created_at,updated_at) VALUES(?,?,1,101,'student','material','Synthetic.pdf','application/pdf',1,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP())",attachment,upload,hash,new byte[]{1});
        assertEquals(1,jdbc.update("UPDATE file_uploads SET state='completed',attachment_id=? WHERE id=?",attachment,upload));
        assertEquals(1,jdbc.update("UPDATE file_attachments SET scan_status='clean',content_sha256=? WHERE id=?",hash,attachment));
        assertEquals(1,jdbc.update("UPDATE file_cleanup_jobs SET state='cancelled' WHERE id=?",cleanup));
        jdbc.update("INSERT INTO material_files(id,user_id,campus_id,file_hash,size_bytes,mime_type,current_attachment_id,created_at,updated_at) VALUES(?,101,1,?,1,'application/pdf',?,UTC_TIMESTAMP(),UTC_TIMESTAMP())",file,hash,attachment);
        jdbc.update("INSERT INTO material_versions(id,file_id,version_no,attachment_id,file_hash,created_at) VALUES(?,?,1,?,?,UTC_TIMESTAMP())",version,file,attachment,hash);
        assertEquals("clean",jdbc.queryForObject("SELECT scan_status FROM file_attachments WHERE id=?",String.class,attachment));
        assertEquals("completed",jdbc.queryForObject("SELECT state FROM file_uploads WHERE id=?",String.class,upload));
        assertEquals("cancelled",jdbc.queryForObject("SELECT state FROM file_cleanup_jobs WHERE id=?",String.class,cleanup));
        assertEquals(hash,jdbc.queryForObject("SELECT file_hash FROM material_versions WHERE id=?",String.class,version));
    }

    @Test void minimumAppCannotMutateVersionsAuditEventsOrReadConverterState()throws Exception {
        try(var connection=DriverManager.getConnection(url,app,appPassword);var statement=connection.createStatement()){
            for(String table:List.of("material_versions","borrow_events","campus_issue_events","lost_found_events","admin_audit_logs")){
                String column=switch(table){case "material_versions"->"version_no";case "borrow_events"->"actor_id";case "campus_issue_events"->"operator_id";case "lost_found_events"->"actor_id";default->"id";};
                assertEquals(1142,assertThrows(SQLException.class,()->statement.executeUpdate("UPDATE "+table+" SET "+column+"="+column+" WHERE id=0"),table+" UPDATE must be denied").getErrorCode());
                assertEquals(1142,assertThrows(SQLException.class,()->statement.executeUpdate("DELETE FROM "+table+" WHERE id=0"),table+" DELETE must be denied").getErrorCode());
            }
            assertEquals(1142,assertThrows(SQLException.class,()->statement.executeQuery("SELECT * FROM file_key_migrations")).getErrorCode());
            for(String table:List.of("file_uploads","file_attachments","file_cleanup_jobs","team_open_roles"))
                assertEquals(1142,assertThrows(SQLException.class,()->statement.executeUpdate("DELETE FROM "+table+" WHERE id=0")).getErrorCode());
            assertDoesNotThrow(()->statement.executeUpdate("UPDATE admin_credentials SET last_totp_step=last_totp_step WHERE user_id=0"));
            assertEquals(1142,assertThrows(SQLException.class,()->statement.executeUpdate("DELETE FROM admin_credentials WHERE user_id=0")).getErrorCode());
            assertEquals(1143,assertThrows(SQLException.class,()->statement.executeUpdate("UPDATE admin_credentials SET mfa_seed_encrypted=mfa_seed_encrypted WHERE user_id=0")).getErrorCode());
            for(String table:List.of("campus_issue_events","team_open_roles","lost_found_events","blocks","credit_disputes"))
                assertDoesNotThrow(()->statement.executeQuery("SELECT COUNT(*) FROM "+table));
        }
    }

    @Test void minimumAppBorrowRequiresBothPeopleForHandoverAndReturn()throws Exception {
        long post=ids.nextId();
        jdbc.update("INSERT INTO mutual_aid_posts(id,campus_id,user_id,category,title,detail_json,status,source_type,created_at,updated_at) VALUES(?,1,101,'borrow','Synthetic offer','{\"kind\":\"offer\",\"itemName\":\"book\"}','published','manual',UTC_TIMESTAMP(),UTC_TIMESTAMP())",post);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var owner=mutualService(101);var borrower=mutualService(102);
        var request=tx.execute(s->borrower.borrow(post,new com.caizhitong.mutualaid.MutualAidInputs.BorrowStart(null,null)));
        long id=Long.parseLong(request.id());assertEquals("pending",request.status());
        assertEquals("confirmed",tx.execute(s->owner.borrowAction(id,borrowAction("approve"))).status());
        for(String action:List.of("confirm_handover","confirm_return")){
            String before=action.equals("confirm_handover")?"confirmed":"handed_over",after=action.equals("confirm_handover")?"handed_over":"returned";
            assertEquals(before,tx.execute(s->owner.borrowAction(id,borrowAction(action))).status());
            assertEquals(before,tx.execute(s->owner.borrowAction(id,borrowAction(action))).status());
            assertEquals(after,tx.execute(s->borrower.borrowAction(id,borrowAction(action))).status());
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(DISTINCT actor_id) FROM borrow_events WHERE borrow_request_id=? AND action=?",Integer.class,id,action));
        }
        assertEquals("returned",jdbc.queryForObject("SELECT status FROM borrow_requests WHERE id=?",String.class,id));
    }

    private com.caizhitong.mutualaid.MutualAidInputs.BorrowAction borrowAction(String action) {
        return new com.caizhitong.mutualaid.MutualAidInputs.BorrowAction(mapper.valueToTree(action));
    }

    @Test void nativeBorrowBothOrdersAdvanceFromTwoOldRepeatableReadSnapshots()throws Exception {
        for(long firstUser:new long[]{101,102}){
            long post=ids.nextId(),request=ids.nextId();
            jdbc.update("INSERT INTO mutual_aid_posts(id,campus_id,user_id,category,title,status,source_type,created_at,updated_at) VALUES(?,1,101,'borrow','Synthetic RR borrow','published','manual',UTC_TIMESTAMP(),UTC_TIMESTAMP())",post);
            jdbc.update("INSERT INTO borrow_requests(id,post_id,borrower_id,status,created_at,updated_at) VALUES(?,?,102,'confirmed',UTC_TIMESTAMP(),UTC_TIMESTAMP())",request,post);
            for(String action:List.of("confirm_handover","confirm_return")){
                String before=action.equals("confirm_handover")?"confirmed":"handed_over";
                String after=action.equals("confirm_handover")?"handed_over":"returned";
                var ready=new java.util.concurrent.CountDownLatch(2);
                var go=new java.util.concurrent.CountDownLatch(1);
                var firstCommitted=new java.util.concurrent.CountDownLatch(1);
                try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
                    var first=pool.submit(()->{
                        try{return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx->{
                            oldBorrowSnapshot(request,action,ready,go);
                            assertEquals(before,mutualService(firstUser).borrowAction(request,borrowAction(action)).status());
                            return mutualService(firstUser).borrowAction(request,borrowAction(action)).status();
                        });}finally{firstCommitted.countDown();}
                    });
                    var second=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx->{
                        oldBorrowSnapshot(request,action,ready,go);
                        await(firstCommitted);
                        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM borrow_events WHERE borrow_request_id=? AND action=?",Integer.class,request,action),"Second transaction must retain its old RR snapshot");
                        return mutualService(firstUser==101?102:101).borrowAction(request,borrowAction(action)).status();
                    }));
                    assertTrue(ready.await(8,java.util.concurrent.TimeUnit.SECONDS));go.countDown();
                    assertEquals(before,first.get(15,java.util.concurrent.TimeUnit.SECONDS));
                    assertEquals(after,second.get(15,java.util.concurrent.TimeUnit.SECONDS));
                }finally{go.countDown();}
                assertEquals(after,jdbc.queryForObject("SELECT status FROM borrow_requests WHERE id=?",String.class,request));
                assertEquals(2,jdbc.queryForObject("SELECT COUNT(DISTINCT actor_id) FROM borrow_events WHERE borrow_request_id=? AND action=?",Integer.class,request,action));
                assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM borrow_events WHERE borrow_request_id=? AND action=?",Integer.class,request,action),"One person's retry remains immutable evidence, not a second participant");
            }
        }
    }

    private void oldBorrowSnapshot(long request,String action,java.util.concurrent.CountDownLatch ready,java.util.concurrent.CountDownLatch go){
        assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM borrow_events WHERE borrow_request_id=? AND action=?",Integer.class,request,action));
        ready.countDown();await(go);
    }

    private void await(java.util.concurrent.CountDownLatch latch){
        try{if(!latch.await(8,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Native RR barrier timed out");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
    }

    @Test void nativeDifferentKeyFirstChecklistCreationReturnsOneCompleteWinnerAcrossOldSnapshots()throws Exception {
        long opportunity=ids.nextId();
        jdbc.update("INSERT INTO opportunities(id,campus_id,title,category,status,source_type,rules_version,version,created_at,updated_at) VALUES(?,1,'Synthetic checklist race','research','published','manual',0,0,UTC_TIMESTAMP(),UTC_TIMESTAMP())",opportunity);
        jdbc.update("INSERT INTO opportunity_material_items(id,opportunity_id,name,required,sort_order,created_at) VALUES(?,?,'Transcript',TRUE,0,UTC_TIMESTAMP()),(?,?,'Application',FALSE,1,UTC_TIMESTAMP())",ids.nextId(),opportunity,ids.nextId(),opportunity);
        var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        // Only coordinate after the actual SQL sees no row; all SQL, transactions and locks remain native.
        var coordinated=org.mockito.Mockito.spy(jdbc);
        org.mockito.Mockito.doAnswer(call->{
            var rows=(List<?>)call.callRealMethod();
            if(rows.isEmpty()){ready.countDown();await(go);}return rows;
        }).when(coordinated).queryForList("SELECT id FROM material_checklists WHERE user_id=? AND opportunity_id=?",Long.class,101L,opportunity);
        var actor=nativeActor(101);
        var opportunities=new com.caizhitong.opportunity.OpportunityService(jdbc,actor,null,null,crypto,mapper);
        var service=new com.caizhitong.material.checklist.ChecklistService(coordinated,actor,opportunities,ids,java.time.Clock.systemUTC(),mapper);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->nativeChecklistCreate(service,opportunity,"first-key"));
            var second=pool.submit(()->nativeChecklistCreate(service,opportunity,"different-key"));
            assertTrue(ready.await(8,java.util.concurrent.TimeUnit.SECONDS));go.countDown();
            var a=first.get(15,java.util.concurrent.TimeUnit.SECONDS);var b=second.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(a,b);assertEquals("draft",a.status());assertEquals(0,a.version());
            assertEquals(Set.of("Transcript","Application"),a.items().stream().map(com.caizhitong.material.checklist.ChecklistService.Item::itemName).collect(java.util.stream.Collectors.toSet()));
            assertEquals(Map.of("Transcript",true,"Application",false),a.items().stream().collect(java.util.stream.Collectors.toMap(com.caizhitong.material.checklist.ChecklistService.Item::itemName,com.caizhitong.material.checklist.ChecklistService.Item::required)));
            assertTrue(a.items().stream().allMatch(item->item.materialVersionId()==null&&item.status().equals("missing")));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM material_checklists WHERE user_id=101 AND opportunity_id=?",Integer.class,opportunity));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM material_checklist_items WHERE checklist_id=?",Integer.class,Long.parseLong(a.id())));
            assertEquals(a,nativeChecklistCreate(service,opportunity,"first-key"));
            assertEquals(a,nativeChecklistCreate(service,opportunity,"different-key"));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency_records WHERE user_id=101 AND request_digest=? AND response_type=? AND status='complete'",Integer.class,"a".repeat(64),com.caizhitong.material.checklist.ChecklistService.Checklist.class.getName()));
        }finally{go.countDown();}
    }

    private com.caizhitong.material.checklist.ChecklistService.Checklist nativeChecklistCreate(com.caizhitong.material.checklist.ChecklistService service,long opportunity,String key){
        var actor=nativeActor(101);
        var executor=new com.caizhitong.common.idempotency.IdempotencyExecutor(jdbc,transactions,ids,crypto,mapper,actor,java.time.Clock.systemUTC());
        var command=new com.caizhitong.common.idempotency.IdempotencyContext("PUT","/api/v1/opportunities/"+opportunity+"/checklist",key,"a".repeat(64));
        return executor.execute(command,com.caizhitong.material.checklist.ChecklistService.Checklist.class,()->{
            actor.requireStudent();
            assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=101",Integer.class));
        },()->service.getOrCreate(Long.toString(opportunity)));
    }

    @Test void unsignedTeamBoundaryBindsActualApplicantAndSupportsLeaveWithMinimumAppGrants()throws Exception {
        var captain=teamService(101);var applicant=teamService(102);var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        String team=tx.execute(s->captain.create(new com.caizhitong.team.TeamInputs.Create(mapper.readTree("\"Synthetic unsigned team\""),null,null,mapper.readTree("[{\"role\":\"Writer\",\"quantity\":1}]"))).id());
        assertInstanceOf(java.math.BigInteger.class,jdbc.queryForMap("SELECT campus_id,leader_id FROM teams WHERE id=?",Long.parseLong(team)).get("campus_id"));
        assertInstanceOf(java.math.BigInteger.class,jdbc.queryForMap("SELECT leader_id FROM teams WHERE id=?",Long.parseLong(team)).get("leader_id"));
        String role=jdbc.queryForObject("SELECT id FROM team_open_roles WHERE team_id=?",String.class,Long.parseLong(team));
        var application=tx.execute(s->applicant.apply(new com.caizhitong.team.TeamInputs.Apply(mapper.readTree("\""+team+"\""),null,mapper.readTree("\""+role+"\""))));
        assertEquals("102",application.applicant().id());assertEquals(role,application.openRoleId());
        tx.execute(s->captain.decide(Long.parseLong(application.id()),new com.caizhitong.team.TeamInputs.Decision(mapper.readTree("\"approve\""))));
        assertTrue(tx.execute(s->applicant.leave(Long.parseLong(team))).left());
        assertEquals("recruiting",jdbc.queryForObject("SELECT status FROM teams WHERE id=?",String.class,Long.parseLong(team)));
        try(var connection=DriverManager.getConnection(url,app,appPassword);var statement=connection.createStatement()){
            assertThrows(SQLException.class,()->statement.executeUpdate("DELETE FROM team_open_roles WHERE id=0"));
            assertThrows(SQLException.class,()->statement.executeUpdate("UPDATE lost_found_events SET description='changed' WHERE id=0"));
            assertThrows(SQLException.class,()->statement.executeUpdate("DELETE FROM lost_found_events WHERE id=0"));
            assertDoesNotThrow(()->statement.executeQuery("SELECT COUNT(*) FROM lost_found_events"));
        }
    }

    @Test void nativeConcurrentLastRoleUsesCurrentRowsAfterBothRepeatableReadSnapshots()throws Exception {
        nativeUser(103);var fixture=nativeTeamApplications(false);long team=fixture[0],first=fixture[1],second=fixture[2];
        var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->nativeApproval(first,team,ready,go));var b=pool.submit(()->nativeApproval(second,team,ready,go));
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));go.countDown();
            var results=List.of(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(status->status==200).count());assertEquals(1,results.stream().filter(status->status==409).count());
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id=?",Integer.class,team));
        assertEquals("recruiting",jdbc.queryForObject("SELECT status FROM teams WHERE id=?",String.class,team)); // second distinct role is still free
    }

    @Test void nativeLegacyTotalCapacityCannotUseEarlierSnapshot()throws Exception {
        nativeUser(103);var fixture=nativeTeamApplications(false);long team=fixture[0],first=fixture[1],second=fixture[2];
        jdbc.update("UPDATE team_applications SET open_role_id=(SELECT MAX(id) FROM team_open_roles WHERE team_id=?) WHERE id=?",team,second);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(outer->{
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id=?",Integer.class,team));
            var inner=new org.springframework.transaction.support.TransactionTemplate(transactions);inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            inner.execute(s->teamService(101).decide(first,new com.caizhitong.team.TeamInputs.Decision(mapper.readTree("\"approve\""))));
            // A historical import with unknown role consumes total capacity; it does not invent role ownership.
            inner.executeWithoutResult(s->jdbc.update("INSERT INTO team_members(id,team_id,user_id,status,created_at) VALUES(?,?,900,'member',UTC_TIMESTAMP())",ids.nextId(),team));
            var error=assertThrows(com.caizhitong.common.BusinessException.class,()->teamService(101).decide(second,new com.caizhitong.team.TeamInputs.Decision(mapper.readTree("\"approve\""))));assertEquals(409,error.getStatus());
        });
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id=?",Integer.class,team));
    }

    @Test void nativeLostEventsRemainTwoPartyAcrossEarlierRepeatableReadSnapshot(){
        long lost=ids.nextId(),found=ids.nextId(),match=ids.nextId();
        for(long[] pair:List.of(new long[]{lost,101},new long[]{found,102}))jdbc.update("INSERT INTO mutual_aid_posts(id,campus_id,user_id,category,title,status,source_type,created_at,updated_at) VALUES(?,1,?,'lost_found','Synthetic lost','published','manual',UTC_TIMESTAMP(),UTC_TIMESTAMP())",pair[0],pair[1]);
        jdbc.update("INSERT INTO lost_found_matches(id,lost_post_id,found_post_id,status,created_at) VALUES(?,?,?,'verified',UTC_TIMESTAMP())",match,lost,found);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(outer->{
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM lost_found_events WHERE match_id=?",Integer.class,match));
            var inner=new org.springframework.transaction.support.TransactionTemplate(transactions);inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertEquals("verified",inner.execute(s->mutualService(101).matchAction(match,new com.caizhitong.mutualaid.MutualAidInputs.MatchAction(mapper.readTree("\"confirm_return\""),mapper.readTree("\"Synthetic native evidence\"")))).status());
            var result=mutualService(102).matchAction(match,new com.caizhitong.mutualaid.MutualAidInputs.MatchAction(mapper.readTree("\"confirm_return\""),null));
            assertEquals("returned",result.status());assertEquals("confirmed",result.confirmationStatus());
        });
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM lost_found_events WHERE match_id=?",Integer.class,match));
        assertEquals("Synthetic native evidence",jdbc.queryForObject("SELECT description FROM lost_found_events WHERE match_id=? AND actor_id=101",String.class,match));
    }

    @Test void nativeLostDuplicateReturnsCurrentIgnoredStateAfterEarlierSnapshot(){
        long lost=ids.nextId(),found=ids.nextId(),match=ids.nextId();
        for(long[] pair:List.of(new long[]{lost,101},new long[]{found,102}))jdbc.update("INSERT INTO mutual_aid_posts(id,campus_id,user_id,category,title,status,source_type,created_at,updated_at) VALUES(?,1,?,'lost_found','Synthetic retry','published','manual',UTC_TIMESTAMP(),UTC_TIMESTAMP())",pair[0],pair[1]);
        jdbc.update("INSERT INTO lost_found_matches(id,lost_post_id,found_post_id,status,created_at) VALUES(?,?,?,'verified',UTC_TIMESTAMP())",match,lost,found);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.execute(s->mutualService(101).matchAction(match,new com.caizhitong.mutualaid.MutualAidInputs.MatchAction(mapper.readTree("\"confirm_return\""),null)));
        tx.executeWithoutResult(outer->{
            assertEquals("verified",jdbc.queryForObject("SELECT status FROM lost_found_matches WHERE id=?",String.class,match));
            var inner=new org.springframework.transaction.support.TransactionTemplate(transactions);inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            inner.execute(s->mutualService(102).matchAction(match,new com.caizhitong.mutualaid.MutualAidInputs.MatchAction(mapper.readTree("\"ignore\""),null)));
            assertEquals("ignored",mutualService(101).matchAction(match,new com.caizhitong.mutualaid.MutualAidInputs.MatchAction(mapper.readTree("\"confirm_return\""),null)).status());
        });
        assertEquals("ignored",jdbc.queryForObject("SELECT status FROM lost_found_matches WHERE id=?",String.class,match));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM lost_found_events WHERE match_id=?",Integer.class,match));
    }

    private void nativeUser(long user){if(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=?",Integer.class,user)==0)jdbc.update("INSERT INTO users(id,campus_id,status,created_at,updated_at) VALUES(?,1,'active',UTC_TIMESTAMP(),UTC_TIMESTAMP())",user);}
    private long[] nativeTeamApplications(boolean legacy){
        long team=ids.nextId(),role=ids.nextId(),role2=ids.nextId(),a=ids.nextId(),b=ids.nextId();
        jdbc.update("INSERT INTO teams(id,campus_id,leader_id,title,status,created_at,updated_at) VALUES(?,1,101,'Synthetic race','recruiting',UTC_TIMESTAMP(),UTC_TIMESTAMP())",team);
        jdbc.update("INSERT INTO team_members(id,team_id,user_id,status,created_at) VALUES(?,?,101,'member',UTC_TIMESTAMP())",ids.nextId(),team);
        if(legacy)jdbc.update("INSERT INTO team_members(id,team_id,user_id,status,created_at) VALUES(?,?,900,'member',UTC_TIMESTAMP())",ids.nextId(),team);
        for(long r:new long[]{role,role2})jdbc.update("INSERT INTO team_open_roles(id,team_id,role,quantity,active,created_at) VALUES(?,?,'Writer',1,TRUE,UTC_TIMESTAMP())",r,team);
        jdbc.update("INSERT INTO team_applications(id,team_id,user_id,open_role_id,status,created_at,updated_at) VALUES(?,?,102,?,'pending',UTC_TIMESTAMP(),UTC_TIMESTAMP()),(?,?,103,?,'pending',UTC_TIMESTAMP(),UTC_TIMESTAMP())",a,team,role,b,team,legacy?role2:role);
        return new long[]{team,a,b};
    }
    private int nativeApproval(long application,long team,java.util.concurrent.CountDownLatch ready,java.util.concurrent.CountDownLatch go){
        try{return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
            assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
            jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id=?",Integer.class,team);ready.countDown();
            try{if(!go.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Native team snapshot barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            teamService(101).decide(application,new com.caizhitong.team.TeamInputs.Decision(mapper.readTree("\"approve\"")));return 200;
        });}catch(com.caizhitong.common.BusinessException error){return error.getStatus();}
    }
    private CurrentActor nativeActor(long user){var actor=new Actor(user,1,Set.of(),true,true,"active",SessionKind.STUDENT);var current=org.mockito.Mockito.mock(CurrentActor.class);org.mockito.Mockito.when(current.require()).thenReturn(actor);org.mockito.Mockito.when(current.requireStudent()).thenReturn(actor);org.mockito.Mockito.when(current.optional()).thenReturn(Optional.of(actor));return current;}
    private com.caizhitong.team.TeamService teamService(long user){return new com.caizhitong.team.TeamService(jdbc,nativeActor(user),ids,java.time.Clock.systemUTC(),mapper,null,crypto);}
    private com.caizhitong.mutualaid.MutualAidService mutualService(long user){return new com.caizhitong.mutualaid.MutualAidService(jdbc,nativeActor(user),ids,java.time.Clock.systemUTC(),mapper,null,crypto,null,new com.caizhitong.resource.ServiceSources(jdbc));}

    @BeforeEach void seed(){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM campuses WHERE id=1",Integer.class)>0){jdbc.update("UPDATE student_verifications SET status='verified'");return;}
        jdbc.update("INSERT INTO campuses(id,name,code,created_at,updated_at) VALUES(1,'Synthetic C','c-test',UTC_TIMESTAMP(),UTC_TIMESTAMP()),(2,'Synthetic Other','c-other',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        for(int user:new int[]{101,102,900})jdbc.update("INSERT INTO users(id,campus_id,status,created_at,updated_at) VALUES(?,1,'active',UTC_TIMESTAMP(),UTC_TIMESTAMP())",user);
        for(int user:new int[]{101,102}){
            jdbc.update("INSERT INTO user_phones(id,user_id,phone_encrypted,phone_search,verified,created_at) VALUES(?,?,?,?,1,UTC_TIMESTAMP())",user,user,new byte[]{1},"synthetic-phone-"+user);
            jdbc.update("INSERT INTO authorizations(id,user_id,type,scope,status,granted_at,created_at) VALUES(?,?,'phone','*','granted',UTC_TIMESTAMP(),UTC_TIMESTAMP())",user+1000,user);
            jdbc.update("INSERT INTO student_verifications(id,user_id,campus_id,student_id_encrypted,student_id_search,status,created_at,updated_at) VALUES(?,?,1,?,?,'verified',UTC_TIMESTAMP(),UTC_TIMESTAMP())",user+3000,user,new byte[]{1},"synthetic-student-"+user);
        }
        jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id,created_at) SELECT 900,900,id,1,UTC_TIMESTAMP() FROM roles WHERE code='security_investigator'");
    }
    @Test void minimumPrivilegeAppCanMaintainAnonymousOwnershipWithoutMappingSelect()throws Exception {
        try(var connection=DriverManager.getConnection(url,app,appPassword);var statement=connection.createStatement()){
            assertEquals(1142,assertThrows(SQLException.class,()->statement.executeQuery("SELECT * FROM pseudo_identities")).getErrorCode());
            assertEquals(1142,assertThrows(SQLException.class,()->statement.executeQuery("SELECT * FROM security_investigations")).getErrorCode());
        }
        String owner="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();
        String other="Bearer "+sessions.issue(102,1,SessionKind.STUDENT).accessToken();
        String body=mvc.perform(post("/api/v1/community/posts").header("Authorization",owner).header("Idempotency-Key","mysql-c-create").contentType("application/json").content("{\"title\":\"Synthetic\",\"content\":\"Synthetic\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=mapper.readTree(body).path("data").path("id").asString();
        assertFalse(jdbc.queryForObject("CALL community_owner_check(1,'community_post',?,'synthetic-invalid-proof')",Boolean.class,Long.parseLong(id)));
        assertTrue(ownership.owns(new Actor(101,1,Set.of(),true,true,"active",SessionKind.STUDENT),"community_post",Long.parseLong(id)));
        assertFalse(ownership.owns(new Actor(102,1,Set.of(),true,true,"active",SessionKind.STUDENT),"community_post",Long.parseLong(id)));
        var wrongCampus=new Actor(101,2,Set.of(),true,true,"active",SessionKind.STUDENT);
        assertFalse(ownership.owns(wrongCampus,"community_post",Long.parseLong(id)));
        assertFalse(ownership.posts(wrongCampus,null,Long.MAX_VALUE,20).contains(Long.parseLong(id)));
        mvc.perform(get("/api/v1/community/posts/"+id).header("Authorization",owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.canEdit").value(true));
        mvc.perform(get("/api/v1/community/posts/"+id).header("Authorization",other)).andExpect(status().isNotFound());
        jdbc.update("UPDATE student_verifications SET status='pending' WHERE user_id=101");
        mvc.perform(get("/api/v1/me/community/posts").header("Authorization",owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(id));
        mvc.perform(post("/api/v1/community/posts/"+id+"/update").header("Authorization",owner).header("Idempotency-Key","mysql-c-update").contentType("application/json").content("{\"title\":\"Changed\",\"version\":0}")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/community/posts/"+id+"?version=1").header("Authorization",owner).header("Idempotency-Key","mysql-c-delete")).andExpect(status().isOk());
    }
    @Test void investigationConfigurationKeepsPrimaryBusinessTransactions(){
        assertEquals(app+"@%",jdbc.queryForObject("SELECT CURRENT_USER()",String.class));
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
            jdbc.update("INSERT INTO campus_issues(id,campus_id,user_id,title,category,status,visibility,source_type,created_at,updated_at) VALUES(990001,1,101,'Synthetic','other','published','public','manual',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
            jdbc.update("INSERT INTO campus_issue_events(id,issue_id,to_status,operator_id,comment,created_at) VALUES(990001,990001,'published',101,'Synthetic',UTC_TIMESTAMP())");
            tx.setRollbackOnly();
        });
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM campus_issue_events WHERE id=990001",Integer.class));
    }
    @Test void documentedAppCanReadPrivacyBusinessTables(){
        assertAll(()->assertDoesNotThrow(()->jdbc.queryForObject("SELECT COUNT(*) FROM file_attachments",Integer.class)),
            ()->assertDoesNotThrow(()->jdbc.queryForObject("SELECT COUNT(*) FROM mutual_aid_ratings",Integer.class)),
            ()->assertDoesNotThrow(()->jdbc.queryForObject("SELECT COUNT(*) FROM blocks",Integer.class)),
            ()->assertDoesNotThrow(()->jdbc.queryForObject("SELECT COUNT(*) FROM credit_disputes",Integer.class)));
    }

    @Test void investigationUsesHeaderDedicatedAuditAndReceiptOnlyReplay()throws Exception {
        long pid=anonymousPost(1);String key="investigate-replay";String body=investigationBody(pid);
        String first=investigate(body,key,reauth()).andExpect(status().isOk()).andExpect(jsonPath("$.data.subject.userId").value("101")).andReturn().getResponse().getContentAsString();
        String investigationId=mapper.readTree(first).path("data").path("investigationId").asString();
        try(var connection=DriverManager.getConnection(url,investigator,investigatorPassword);var statement=connection.createStatement()){
            assertThrows(SQLException.class,()->statement.executeQuery("SELECT * FROM users"));
            assertThrows(SQLException.class,()->statement.executeUpdate("UPDATE security_investigations SET reason='changed'"));
            try(var result=statement.executeQuery("SELECT COUNT(*) FROM security_investigations WHERE JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.investigationId'))='"+investigationId+"'")){assertTrue(result.next());assertEquals(1,result.getInt(1));}
        }
        investigate(body,key,null).andExpect(status().isForbidden());
        String oneUse=reauth();
        investigate(body,key,oneUse).andExpect(status().isOk()).andExpect(jsonPath("$.data.investigationId").value(investigationId));
        investigate(body,key,oneUse).andExpect(status().isForbidden());
        investigate(body.replace("Synthetic inquiry","Changed inquiry"),key,reauth()).andExpect(status().isConflict());
        var receipt=jdbc.queryForMap("SELECT scope_digest,key_digest,request_digest,response_encrypted,response_type FROM api_idempotency_records WHERE response_type LIKE '%InvestigationReceipt' ORDER BY id DESC LIMIT 1");
        String clear=crypto.decrypt("idempotency-result:"+receipt.get("scope_digest")+":"+receipt.get("key_digest")+":"+receipt.get("request_digest"),(byte[])receipt.get("response_encrypted"));
        assertEquals(Set.of("investigationId"),mapper.readTree(clear).propertyNames());
        assertFalse(clear.contains("userId"));assertFalse(clear.contains("subject"));assertFalse(clear.contains(oneUse));
    }

    @Test void investigationRejectsCrossCampusTargetAndUnrelatedReportBeforeDisclosure()throws Exception {
        long own=anonymousPost(1),other=anonymousPost(2);
        investigate(investigationBody(other),"investigate-other",reauth()).andExpect(status().isNotFound());
        long report=ids.nextId();jdbc.update("INSERT INTO reports(id,campus_id,reporter_id,target_type,target_id,reason,status,created_at,updated_at) VALUES(?,1,101,'community_post',?,'Synthetic','pending',UTC_TIMESTAMP(),UTC_TIMESTAMP())",report,other);
        investigate(investigationBody(own).replace("\"reason\"","\"reportId\":\""+report+"\",\"reason\""),"investigate-report",reauth()).andExpect(status().isNotFound());
        investigate(investigationBody(own).replace("community_post","mutual_aid_post"),"investigate-type",reauth()).andExpect(status().isBadRequest());
    }

    @Test void concurrentSameKeyInvestigationsKeepOneReceiptAndAuditEveryDisclosure()throws Exception {
        String body=investigationBody(anonymousPost(1));String a=reauth(),b=reauth();
        var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->{ready.countDown();go.await();return investigate(body,"concurrent-inquiry",a).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();});
            var second=pool.submit(()->{ready.countDown();go.await();return investigate(body,"concurrent-inquiry",b).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();});
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));go.countDown();
            String id=mapper.readTree(first.get(15,java.util.concurrent.TimeUnit.SECONDS)).path("data").path("investigationId").asString();
            assertEquals(id,mapper.readTree(second.get(15,java.util.concurrent.TimeUnit.SECONDS)).path("data").path("investigationId").asString());
            assertEquals(2,auditCount(id));
        }
    }

    @Test void cachedReceiptCannotRevealIdentityWhenDedicatedAuditInsertFailsAndRetryIsSafe()throws Exception {
        String body=investigationBody(anonymousPost(1)),key="audit-failure-retry",token=reauth();
        assertTrue(schema.matches("czt_abc_c_[a-z0-9_]+"));assertTrue(investigator.matches("czt_c_i_[a-f0-9]{12}"));
        assertNotEquals(app,investigator);assertNotEquals(migrator,investigator);
        assertEquals("abc-review-repairs-20261015",System.getenv("CZT_ABC_C_VERIFIED_LABEL"));
        String rootPassword=System.getenv("CZT_ABC_C_ROOT_PASSWORD");
        String receiptId;
        try(var connection=DriverManager.getConnection(url,"root",rootPassword);var statement=connection.createStatement()){
            try{
                statement.execute("REVOKE INSERT ON "+schema+".security_investigations FROM '"+investigator+"'@'%'");
                String failure=investigate(body,key,token).andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
                assertFalse(failure.contains("subject"));assertFalse(failure.contains("userId"));
                receiptId=lastReceiptId();assertEquals(0,auditCount(receiptId));
            }finally{
                statement.execute("GRANT INSERT ON "+schema+".security_investigations TO '"+investigator+"'@'%'");
                try(var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA='"+schema+"' AND TABLE_NAME='security_investigations' AND PRIVILEGE_TYPE='INSERT' AND GRANTEE=\"'"+investigator+"'@'%'\"")){
                    assertTrue(rows.next());assertEquals(1,rows.getInt(1),"Exact temporary investigator INSERT grant must be restored");
                }
            }
        }catch(SQLException failure){throw new IllegalStateException("Scoped fault injection failed: SQLState="+failure.getSQLState()+", code="+failure.getErrorCode());}
        finally{rootPassword=null;}
        investigate(body,key,token).andExpect(status().isForbidden());
        investigate(body,key,reauth()).andExpect(status().isOk()).andExpect(jsonPath("$.data.investigationId").value(receiptId));
        assertEquals(1,auditCount(receiptId));
    }

    @Test void ownershipNeverCrossesCampusAndCommentsRemainOwnerMaintained()throws Exception {
        long pid=anonymousPost(1);
        assertFalse(ownership.owns(new Actor(101,2,Set.of(),true,true,"active",SessionKind.STUDENT),"community_post",pid));
        assertTrue(ownership.posts(new Actor(101,2,Set.of(),true,true,"active",SessionKind.STUDENT),null,Long.MAX_VALUE,10).stream().noneMatch(id->id==pid));
        String owner="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();String other="Bearer "+sessions.issue(102,1,SessionKind.STUDENT).accessToken();
        String body=mvc.perform(post("/api/v1/community/posts/"+pid+"/comments").header("Authorization",owner).header("Idempotency-Key","mysql-owner-comment").contentType("application/json").content("{\"content\":\"Synthetic\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String comment=mapper.readTree(body).path("data").path("id").asString();
        mvc.perform(delete("/api/v1/community/comments/"+comment).header("Authorization",other).header("Idempotency-Key","mysql-other-comment")).andExpect(status().isNotFound());
        jdbc.update("UPDATE student_verifications SET status='pending' WHERE user_id=101");
        mvc.perform(delete("/api/v1/community/comments/"+comment).header("Authorization",owner).header("Idempotency-Key","mysql-delete-comment")).andExpect(status().isOk());
    }

    private String lastReceiptId(){
        var receipt=jdbc.queryForMap("SELECT scope_digest,key_digest,request_digest,response_encrypted FROM api_idempotency_records WHERE response_type LIKE '%InvestigationReceipt' ORDER BY id DESC LIMIT 1");
        return mapper.readTree(crypto.decrypt("idempotency-result:"+receipt.get("scope_digest")+":"+receipt.get("key_digest")+":"+receipt.get("request_digest"),(byte[])receipt.get("response_encrypted"))).path("investigationId").asString();
    }
    private int auditCount(String id)throws SQLException {
        try(var connection=DriverManager.getConnection(url,investigator,investigatorPassword);var statement=connection.prepareStatement("SELECT COUNT(*) FROM security_investigations WHERE JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.investigationId'))=?")){
            statement.setString(1,id);try(var rows=statement.executeQuery()){assertTrue(rows.next());return rows.getInt(1);}
        }
    }

    private long anonymousPost(long campus){
        long post=ids.nextId(),pseudo=ids.nextId();
        jdbc.update("INSERT INTO community_posts(id,campus_id,pseudo_identity_id,author_name,title,content,status,created_at,updated_at) VALUES(?,?,?,'Synthetic alias','Synthetic','Synthetic','published',UTC_TIMESTAMP(),UTC_TIMESTAMP())",post,campus,pseudo);
        jdbc.update("INSERT INTO pseudo_identities(id,post_id,user_id,random_name,created_at,user_id_encrypted,user_id_search) VALUES(?,?,NULL,'Synthetic alias',UTC_TIMESTAMP(),?,?)",pseudo,post,crypto.encrypt("anonymous-identity","101"),HexFormat.of().formatHex(crypto.searchHash("anonymous-identity","101")));
        return post;
    }
    @Test void nativeGovernanceKeepsCampusAuditAndUnsignedIdentifiers()throws Exception {
        jdbc.update("INSERT INTO users(id,campus_id,status,created_at,updated_at) VALUES(901,1,'active',UTC_TIMESTAMP(),UTC_TIMESTAMP()),(201,2,'active',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id,created_at) SELECT 901,901,id,1,UTC_TIMESTAMP() FROM roles WHERE code='campus_admin'");
        long event=ids.nextId(),dispute=ids.nextId(),otherEvent=ids.nextId(),otherDispute=ids.nextId(),wrongOwner=ids.nextId();
        jdbc.update("INSERT INTO credit_events(id,campus_id,user_id,event_type,reason,points,created_at) VALUES(?,1,101,'damage','Synthetic',-1,UTC_TIMESTAMP()),(?,2,201,'damage','Synthetic',-1,UTC_TIMESTAMP())",event,otherEvent);
        jdbc.update("INSERT INTO credit_disputes(id,campus_id,user_id,credit_event_id,reason,created_at) VALUES(?,1,101,?,'Synthetic',UTC_TIMESTAMP()),(?,2,201,?,'Synthetic',UTC_TIMESTAMP()),(?,1,102,?,'Synthetic',UTC_TIMESTAMP())",dispute,event,otherDispute,otherEvent,wrongOwner,event);
        String decision="{\"decision\":\"approve\",\"reason\":\"Synthetic correction\"}";
        adminCommand(901,"/credit-disputes/"+otherDispute+"/decision",decision).andExpect(status().isNotFound());
        adminCommand(901,"/credit-disputes/"+wrongOwner+"/decision",decision).andExpect(status().isNotFound());
        adminCommand(901,"/credit-disputes/"+dispute+"/decision",decision).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("approved"));
        adminCommand(901,"/credit-disputes/"+dispute+"/decision",decision).andExpect(status().isConflict());
        assertEquals("Synthetic correction",jdbc.queryForObject("SELECT reason FROM admin_audit_logs WHERE target_type='credit_dispute' AND target_id=?",String.class,dispute));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM credit_events WHERE event_type='correction' AND related_id=?",Integer.class,event));
        String penalty=mapper.readTree(adminCommand(901,"/penalties","{\"userId\":\"101\",\"type\":\"mute\",\"reason\":\"Synthetic penalty\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        long appeal=ids.nextId();jdbc.update("INSERT INTO appeals(id,user_id,penalty_id,reason,created_at) VALUES(?,101,?,'Synthetic appeal',UTC_TIMESTAMP())",appeal,Long.parseLong(penalty));
        adminCommand(901,"/appeals/"+appeal+"/decision",decision).andExpect(status().isOk());
        assertEquals("lifted",jdbc.queryForObject("SELECT status FROM penalties WHERE id=?",String.class,Long.parseLong(penalty)));
        assertEquals("Synthetic correction",jdbc.queryForObject("SELECT reason FROM admin_audit_logs WHERE target_type='appeal' AND target_id=?",String.class,appeal));
        long post=anonymousPost(1),task=ids.nextId();
        jdbc.update("INSERT INTO moderation_tasks(id,campus_id,target_type,target_id,status,assigned_to,created_at,updated_at) VALUES(?,1,'community_post',?,'assigned',901,UTC_TIMESTAMP(),UTC_TIMESTAMP())",task,post);
        adminCommand(901,"/moderation-tasks/"+task+"/decision",decision).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("resolved"));
    }
    @Test void investigatorCommentAndCurrentPermissionAreCheckedBeforeEveryDisclosure()throws Exception {
        long post=anonymousPost(1);
        String comment=mapper.readTree(mvc.perform(post("/api/v1/community/posts/"+post+"/comments").header("Authorization","Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken())
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"content\":\"Synthetic comment\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        long report=ids.nextId();jdbc.update("INSERT INTO reports(id,campus_id,reporter_id,target_type,target_id,reason,status,created_at,updated_at) VALUES(?,1,102,'community_comment',?,'Synthetic','pending',UTC_TIMESTAMP(),UTC_TIMESTAMP())",report,Long.parseLong(comment));
        String body="{\"targetType\":\"community_comment\",\"targetId\":\""+comment+"\",\"reportId\":\""+report+"\",\"reason\":\"Synthetic inquiry\"}",key="comment-investigation";
        String result=investigate(body,key,reauth()).andExpect(status().isOk()).andExpect(jsonPath("$.data.subject.userId").value("101")).andReturn().getResponse().getContentAsString();
        String investigationId=mapper.readTree(result).path("data").path("investigationId").asString();assertEquals(1,auditCount(investigationId));
        // Keep an admin session valid but remove investigation permission; cached receipt must not disclose.
        jdbc.update("UPDATE user_roles SET role_id=(SELECT id FROM roles WHERE code='business_operator') WHERE user_id=900");
        try{investigate(body,key,reauth()).andExpect(status().isForbidden()).andExpect(jsonPath("$.data.subject").doesNotExist());}
        finally{jdbc.update("UPDATE user_roles SET role_id=(SELECT id FROM roles WHERE code='security_investigator') WHERE user_id=900");}
        assertEquals(1,auditCount(investigationId));
    }
    private org.springframework.test.web.servlet.ResultActions adminCommand(long user,String route,String body)throws Exception {
        var csrf=mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andReturn().getResponse();
        return mvc.perform(post("/api/v1/admin"+route).secure(true).cookie(csrf.getCookie("ADMIN-XSRF-TOKEN"))
            .header("X-ADMIN-CSRF",mapper.readTree(csrf.getContentAsString()).path("data").path("token").asString())
            .header("Authorization","Bearer "+sessions.issue(user,1,SessionKind.ADMIN).accessToken()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body));
    }
    private String reauth(){
        byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        assertTrue(secrets.claim("admin-reauth",HexFormat.of().formatHex(crypto.searchHash("admin-reauth",token)),"900:1:"+(java.time.Instant.now().getEpochSecond()+300),java.time.Duration.ofMinutes(5)));return token;
    }
    private String investigationBody(long id){return "{\"targetType\":\"community_post\",\"targetId\":\""+id+"\",\"reason\":\"Synthetic inquiry\"}";}
    private org.springframework.test.web.servlet.ResultActions investigate(String body,String key,String reauth)throws Exception {
        var csrf=mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andReturn().getResponse();
        var request=post("/api/v1/admin/security-investigations").secure(true).cookie(csrf.getCookie("ADMIN-XSRF-TOKEN"))
            .header("X-ADMIN-CSRF",mapper.readTree(csrf.getContentAsString()).path("data").path("token").asString())
            .header("Authorization","Bearer "+sessions.issue(900,1,SessionKind.ADMIN).accessToken()).header("Idempotency-Key",key).contentType("application/json").content(body);
        if(reauth!=null)request.header("X-Reauth-Token",reauth);
        return mvc.perform(request);
    }
}
