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
        assertDoesNotThrow(()->jdbc.queryForObject("CALL community_owner_check(1,'community_post',?,'synthetic-invalid-proof')",Boolean.class,Long.parseLong(id)));
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
