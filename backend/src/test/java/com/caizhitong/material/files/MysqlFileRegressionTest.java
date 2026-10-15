package com.caizhitong.material.files;

import com.caizhitong.common.*;
import com.caizhitong.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Only the separately owned loopback test database, with actual Connector/J defaults and InnoDB RR. */
@EnabledIfEnvironmentVariable(named="CZT_MYSQL_FILES_TEST",matches="true")
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@Import({FileHttpTest.Fixtures.class,MysqlFileRegressionTest.Gates.class})
class MysqlFileRegressionTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:13306/czt_task4_review?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8");
        r.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        r.add("spring.datasource.username",()->"czt_task4_test");r.add("spring.datasource.password",MysqlFileRegressionTest::password);
        r.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone = '+00:00'");
        r.add("spring.jpa.hibernate.ddl-auto",()->"none");
        r.add("spring.flyway.enabled",()->true);r.add("spring.flyway.user",()->"czt_task4_test");r.add("spring.flyway.password",MysqlFileRegressionTest::password);
    }
    static String password(){String value=System.getenv("CZT_MYSQL_TEST_PASSWORD");if(value==null||value.isBlank())throw new IllegalStateException("Isolated test password required");return value;}
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired SnowflakeIds ids;
    @Autowired SensitiveDataCrypto crypto;
    @Autowired SessionTokenService sessions;
    @Autowired RetentionGate gate;
    private long campus,user;
    private String bearer;
    private final JsonMapper mapper=new JsonMapper();
    @BeforeEach void seed(){
        assertEquals("czt_task4_review",jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
        campus=ids.nextId();user=ids.nextId();
        jdbc.update("INSERT INTO campuses(id,name,code,status,created_at,updated_at) VALUES(?,'隔离测试',?,'active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",campus,"test-"+campus);
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(?,?,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user,campus);
        bearer="Bearer "+sessions.issue(user,campus,SessionKind.STUDENT).accessToken();
        gate.reset();
    }
    @Test void connectorDatetimeCompletesUploadWithoutGlobalDriverWorkaround()throws Exception {
        String result=mvc.perform(post("/api/v1/files/upload-intents").header("Authorization",bearer).contentType("application/json")
                .content("{\"purpose\":\"avatar\",\"fileName\":\"test\",\"mimeType\":\"image/png\",\"sizeBytes\":3}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var intent=mapper.readTree(result).path("data");String upload=intent.path("uploadId").asString();
        assertInstanceOf(LocalDateTime.class,jdbc.queryForMap("SELECT expires_at FROM file_uploads WHERE id=?",Long.parseLong(upload)).get("expires_at"));
        mvc.perform(multipart("/api/v1/files/uploads/"+upload).file(new org.springframework.mock.web.MockMultipartFile("file","test","image/png",new byte[]{1,2,3})).header("Authorization","Upload "+intent.path("uploadToken").asString())).andExpect(status().isCreated());
        command("/files/uploads/"+upload+"/complete","{}","mysql-complete").andExpect(status().isAccepted()).andExpect(jsonPath("$.data.scanStatus").value("pending"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM file_attachments WHERE upload_id=?",Integer.class,Long.parseLong(upload)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM async_jobs WHERE job_type='file_scan' AND owner_user_id=?",Integer.class,user));
    }
    @Test void rrDeleteSeesReferenceCommittedAfterItsSnapshotBeforeAttachmentLock()throws Exception {
        long attachment=cleanMaterialAttachment();
        String material=mapper.readTree(command("/materials","{\"attachmentId\":\""+attachment+"\"}","mysql-material-a").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        gate.target=Long.parseLong(material);
        try(var pool=Executors.newSingleThreadExecutor()){
            Future<Integer> deleting=pool.submit(()->mvc.perform(delete("/api/v1/materials/"+material+"?version=0").header("Authorization",bearer).header("Idempotency-Key","mysql-delete-a")).andReturn().getResponse().getStatus());
            try {
                assertTrue(gate.snapshotEstablished.await(5,TimeUnit.SECONDS),"D must establish its RR snapshot before C commits");
                String second=mapper.readTree(command("/materials","{\"attachmentId\":\""+attachment+"\"}","mysql-material-b").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
                assertEquals("active",jdbc.queryForObject("SELECT status FROM material_files WHERE id=?",String.class,Long.parseLong(second)));
            }finally{gate.creatorCommitted.countDown();}
            assertEquals(200,deleting.get(5,TimeUnit.SECONDS));
        }
        assertEquals("active",jdbc.queryForObject("SELECT state FROM file_attachments WHERE id=?",String.class,attachment),"B's committed immutable version must retain the shared attachment");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM file_cleanup_jobs WHERE attachment_id=?",Integer.class,attachment));
    }
    private long cleanMaterialAttachment(){
        long upload=ids.nextId(),attachment=ids.nextId();byte[] key=crypto.encrypt("file-object-key","review/"+UUID.randomUUID());
        jdbc.update("INSERT INTO file_uploads(id,campus_id,owner_user_id,access_domain,purpose,file_name,mime_type,size_bytes,token_digest,expires_at,state,object_key_encrypted,original_sha256,created_at,updated_at) VALUES(?,?,?,'student','material','test','application/pdf',3,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE),'uploaded',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",upload,campus,user,HexFormat.of().formatHex(crypto.searchHash("test-upload",Long.toString(upload))),key,"a".repeat(64));
        jdbc.update("INSERT INTO file_attachments(id,upload_id,campus_id,owner_user_id,access_domain,purpose,file_name,mime_type,size_bytes,original_sha256,content_sha256,object_key_encrypted,scan_status,state,created_at,updated_at) VALUES(?,?,?,?,'student','material','test','application/pdf',3,?,?,?,'clean','active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",attachment,upload,campus,user,"a".repeat(64),"b".repeat(64),key);
        return attachment;
    }
    private ResultActions command(String path,String body,String key)throws Exception{return mvc.perform(post("/api/v1"+path).header("Authorization",bearer).header("Idempotency-Key",key).contentType("application/json").content(body));}
    static class RetentionGate implements MaterialRetentionPolicy {
        volatile long target;
        CountDownLatch snapshotEstablished,creatorCommitted;
        void reset(){target=0;snapshotEstablished=new CountDownLatch(1);creatorCommitted=new CountDownLatch(1);}
        public boolean mustRetain(long id){
            if(id==target){snapshotEstablished.countDown();try{if(!creatorCommitted.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Creator transaction did not commit");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
            return false;
        }
    }
    @TestConfiguration static class Gates {@Bean RetentionGate retentionGate(){return new RetentionGate();}}
}
