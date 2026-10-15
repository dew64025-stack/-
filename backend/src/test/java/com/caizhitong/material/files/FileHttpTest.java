package com.caizhitong.material.files;

import com.caizhitong.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:files_http;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(FileHttpTest.Fixtures.class)
@Sql(scripts={"/identity-schema.sql","/files-schema.sql"},executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class FileHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokenService sessions;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired TestScanner scanner;
    @Autowired com.caizhitong.common.SensitiveDataCrypto crypto;
    @Autowired TestStorage storage;
    @BeforeEach void seed() {
        scanner.result=com.caizhitong.integration.scanner.ScanResult.CLEAN;
        storage.failDelete=false;
        for(String table:List.of("api_idempotency_records","admin_audit_logs","growth_records","material_usage_records","material_checklist_items","material_versions","material_files","async_jobs","student_roster","file_cleanup_jobs","file_attachments","student_verifications","authorizations","user_roles","roles","campus_configs","campus_organizations"))jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM file_uploads");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM campuses");
        jdbc.update("INSERT INTO campuses VALUES(1,'大学','one','active',NULL)");
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(101,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }
    @Test void materialIntentIssuesUploadCredentialAndStringId() throws Exception {
        String bearer="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();
        mvc.perform(post("/api/v1/materials/upload-intents").header("Authorization",bearer)
                .contentType("application/json").content("{\"fileName\":\"proof.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":100}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.uploadId").isString())
                .andExpect(jsonPath("$.data.uploadToken").isString()).andExpect(jsonPath("$.data.maxSizeBytes").value(20971520));
    }
    @Test void uploadCredentialAllowsBinaryOnceAndBearerCannotReplaceIt() throws Exception {
        String bearer="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();
        String result=mvc.perform(post("/api/v1/files/upload-intents").header("Authorization",bearer)
                .contentType("application/json").content("{\"purpose\":\"avatar\",\"fileName\":\"avatar.png\",\"mimeType\":\"image/png\",\"sizeBytes\":3}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var data=new tools.jackson.databind.json.JsonMapper().readTree(result).path("data");
        String path="/api/v1/files/uploads/"+data.path("uploadId").asString();
        var file=new org.springframework.mock.web.MockMultipartFile("file","avatar.png","image/png",new byte[]{1,2,3});
        mvc.perform(multipart(path).file(file).header("Authorization",bearer)).andExpect(status().isUnauthorized());
        mvc.perform(multipart(path).file(file).header("Authorization","Upload "+data.path("uploadToken").asString())).andExpect(status().isCreated());
        mvc.perform(multipart(path).file(file).header("Authorization","Upload "+data.path("uploadToken").asString())).andExpect(status().isUnauthorized());
    }
    @Test void completeIsAtomicAndPendingFileCannotBeDownloadedOrReadByAnotherOwner() throws Exception {
        String bearer="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();
        var data=intent(bearer,"avatar","image/png",new byte[]{1,2,3});
        String path="/api/v1/files/uploads/"+data.path("uploadId").asString();
        mvc.perform(multipart(path).file(new org.springframework.mock.web.MockMultipartFile("file","test","image/png",new byte[]{1,2,3}))
                .header("Authorization","Upload "+data.path("uploadToken").asString())).andExpect(status().isCreated());
        String body=mvc.perform(post(path+"/complete").header("Authorization",bearer).header("Idempotency-Key","complete-once").contentType("application/json").content("{}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.scanStatus").value("pending")).andReturn().getResponse().getContentAsString();
        String attachment=new tools.jackson.databind.json.JsonMapper().readTree(body).path("data").path("attachmentId").asString();
        mvc.perform(post(path+"/complete").header("Authorization",bearer).header("Idempotency-Key","complete-once").contentType("application/json").content("{}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.attachmentId").value(attachment));
        Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM file_attachments",Integer.class));
        Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM async_jobs WHERE job_type='file_scan'",Integer.class));
        mvc.perform(get("/api/v1/files/"+attachment).header("Authorization",bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(attachment)).andExpect(jsonPath("$.data.objectKey").doesNotExist());
        mvc.perform(post("/api/v1/files/"+attachment+"/download-url").header("Authorization",bearer).contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(102,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/v1/files/"+attachment).header("Authorization","Bearer "+sessions.issue(102,1,SessionKind.STUDENT).accessToken())).andExpect(status().isNotFound());
        mvc.perform(post(path+"/complete").header("Authorization","Bearer "+sessions.issue(102,1,SessionKind.STUDENT).accessToken()).header("Idempotency-Key","complete-once").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }
    @Test void materialCreatesAppendedImmutableVersionsAndProtectsRetention() throws Exception {
        String bearer="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();
        var crypto=new com.caizhitong.common.SensitiveDataCrypto(new byte[32],new byte[]{1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0});
        for(long id:List.of(501L,502L))jdbc.update("INSERT INTO file_attachments(id,upload_id,campus_id,owner_user_id,access_domain,purpose,file_name,mime_type,size_bytes,original_sha256,content_sha256,object_key_encrypted,scan_status,state,created_at,updated_at) VALUES(?,?,1,101,'student','material','test','application/pdf',3,?,?,?,'clean','active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,id,"a".repeat(64),"b".repeat(64),crypto.encrypt("file-object-key","clean/"+id));
        var response=command("/materials",bearer,"{\"attachmentId\":\"501\",\"tags\":[\"入学\"]}","material-first").andExpect(status().isCreated()).andExpect(jsonPath("$.data.currentVersion.versionNo").value(1)).andReturn();
        String material=new tools.jackson.databind.json.JsonMapper().readTree(response.getResponse().getContentAsString()).path("data").path("id").asString();
        long first=jdbc.queryForObject("SELECT id FROM material_versions",Long.class);
        jdbc.update("INSERT INTO material_checklist_items VALUES(1,?)",first);
        command("/materials/"+material+"/versions",bearer,"{\"attachmentId\":\"502\",\"version\":0}","material-append").andExpect(status().isCreated()).andExpect(jsonPath("$.data.currentVersion.versionNo").value(2));
        Assertions.assertEquals(501,jdbc.queryForObject("SELECT attachment_id FROM material_versions WHERE id=?",Long.class,first));
        mvc.perform(get("/api/v1/materials/"+material).header("Authorization",bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.versions.length()").value(2));
        mvc.perform(get("/api/v1/materials?tag=入学&limit=1").header("Authorization",bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        command("/materials/"+material+"/update",bearer,"{\"tags\":[\"新标签\"],\"version\":1}","material-update").andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(delete("/api/v1/materials/"+material+"?version=2").header("Authorization",bearer).header("Idempotency-Key","material-delete")).andExpect(status().isConflict());
        jdbc.update("DELETE FROM material_checklist_items");
        jdbc.update("INSERT INTO growth_records VALUES(1,?)",Long.parseLong(material));
        mvc.perform(delete("/api/v1/materials/"+material+"?version=2").header("Authorization",bearer).header("Idempotency-Key","growth-retains-material")).andExpect(status().isConflict());
        jdbc.update("DELETE FROM growth_records");
        mvc.perform(delete("/api/v1/materials/"+material+"?version=2").header("Authorization",bearer).header("Idempotency-Key","material-delete")).andExpect(status().isOk()).andExpect(jsonPath("$.data.cleanupStatus").value("pending"));
        Assertions.assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM file_cleanup_jobs",Integer.class));
    }
    tools.jackson.databind.JsonNode intent(String bearer,String purpose,String mime,byte[] bytes)throws Exception {
        String body=new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of("purpose",purpose,"fileName","test","mimeType",mime,"sizeBytes",bytes.length));
        String result=mvc.perform(post("/api/v1/files/upload-intents").header("Authorization",bearer).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.json.JsonMapper().readTree(result).path("data");
    }
    org.springframework.test.web.servlet.ResultActions command(String path,String bearer,String body,String key)throws Exception {
        return mvc.perform(post("/api/v1"+path).header("Authorization",bearer).header("Idempotency-Key",key).contentType("application/json").content(body));
    }
    @Test void rawScanAndFormatValidationAreRequiredBeforeAvatarDownload()throws Exception {
        Assertions.assertTrue(context.containsBean("fileScanWorker"),"A real leased scan worker must process pending attachments");
        String bearer="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();
        var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);byte[] png=out.toByteArray();
        var data=intent(bearer,"avatar","image/png",png);
        String upload="/api/v1/files/uploads/"+data.path("uploadId").asString();
        mvc.perform(multipart(upload).file(new org.springframework.mock.web.MockMultipartFile("file","test","image/png",png)).header("Authorization","Upload "+data.path("uploadToken").asString())).andExpect(status().isCreated());
        String result=command(upload.substring(7)+"/complete",bearer,"{}","avatar-complete").andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id=new tools.jackson.databind.json.JsonMapper().readTree(result).path("data").path("attachmentId").asString();
        context.getBean("fileScanWorker",Runnable.class).run();
        mvc.perform(post("/api/v1/files/"+id+"/download-url").header("Authorization",bearer).contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.url").isString());
        Assertions.assertNotNull(jdbc.queryForObject("SELECT content_sha256 FROM file_attachments",String.class));
        Assertions.assertArrayEquals(png,scanner.original);
        for(int i=0;i<2;i++)command("/me/update",bearer,"{\"avatarAttachmentId\":\""+id+"\",\"version\":0}","avatar-reference").andExpect(status().isOk()).andExpect(jsonPath("$.data.avatarUrl").isString());
        Assertions.assertNull(jdbc.queryForObject("SELECT avatar_url FROM users WHERE id=101",String.class));
        for(var record:jdbc.queryForList("SELECT * FROM api_idempotency_records")){
            String purpose="idempotency-result:"+record.get("scope_digest")+":"+record.get("key_digest")+":"+record.get("request_digest");
            Assertions.assertFalse(crypto.decrypt(purpose,(byte[])record.get("response_encrypted")).contains("signature="));
        }
    }
    @Test void strictIntentTypesNamesMimeAndLimitsAreEnforced()throws Exception {
        String bearer="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();
        for(String body:List.of("{\"fileName\":1,\"mimeType\":\"application/pdf\",\"sizeBytes\":3}","{\"fileName\":\"../p.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":3}","{\"fileName\":\"p\\u0085.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":3}","{\"fileName\":\"p.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":\"3\"}","{\"fileName\":\"p.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":3.0}","{\"fileName\":\"p.pdf\",\"mimeType\":\"application/exe\",\"sizeBytes\":3}","{\"fileName\":\"p.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":3,\"objectKey\":\"x\"}"))
            mvc.perform(post("/api/v1/materials/upload-intents").header("Authorization",bearer).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/materials/upload-intents").header("Authorization",bearer).contentType("application/json").content("{\"fileName\":\"p.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":20971521}")).andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/v1/files/upload-intents").header("Authorization",bearer).contentType("application/json").content("{\"purpose\":\"student_roster\",\"fileName\":\"roster.csv\",\"mimeType\":\"text/csv\",\"sizeBytes\":3}")).andExpect(status().isForbidden());
        command("/materials",bearer,"{\"attachmentId\":501}","numeric-file-id").andExpect(status().isBadRequest());
    }
    @Test void infectedFailedAndSpoofedFormatsNeverBecomeClean()throws Exception {
        String bearer="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();
        for(var result:com.caizhitong.integration.scanner.ScanResult.values()) {
            scanner.result=result;String id=uploaded(bearer,"avatar","image/png",new byte[]{1,2,3});
            Runnable worker=context.getBean("fileScanWorker",Runnable.class);
            for(int attempt=0;attempt<3;attempt++){jdbc.update("UPDATE async_jobs SET next_retry_at=NULL");worker.run();}
            Assertions.assertEquals(result==com.caizhitong.integration.scanner.ScanResult.INFECTED?"infected":"failed",jdbc.queryForObject("SELECT scan_status FROM file_attachments WHERE id=?",String.class,Long.parseLong(id)));
            mvc.perform(post("/api/v1/files/"+id+"/download-url").header("Authorization",bearer).contentType("application/json").content("{}"))
                    .andExpect(status().isConflict());
        }
    }
    @Test void adminNamespaceAndCurrentPurposeRightsGateUploadAndRosterWorker()throws Exception {
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(900,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO roles(id,code) VALUES(7,'campus_admin')");jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id) VALUES(1,900,7,1)");
        jdbc.update("INSERT INTO campus_organizations(id,campus_id,name,type,status) VALUES(1,1,'学院','college','active')");jdbc.update("INSERT INTO campus_configs VALUES(1,1,'profile.allowedGrades','[\"2026\"]')");
        String admin="Bearer "+sessions.issue(900,1,SessionKind.ADMIN).accessToken();byte[] csv="studentNumber,college,grade,status\n20260001,学院,2026,active\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String body=new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of("purpose","student_roster","fileName","test","mimeType","text/csv","sizeBytes",csv.length));
        mvc.perform(post("/api/v1/admin/files/upload-intents").header("Authorization",admin).contentType("application/json").content(body)).andExpect(status().isForbidden());
        var data=new tools.jackson.databind.json.JsonMapper().readTree(adminCommand("/admin/files/upload-intents",admin,body,"roster-intent").andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        String upload="/api/v1/files/uploads/"+data.path("uploadId").asString();
        jdbc.update("DELETE FROM user_roles WHERE user_id=900");
        mvc.perform(multipart(upload).file(new org.springframework.mock.web.MockMultipartFile("file","test","text/csv",csv)).header("Authorization","Upload "+data.path("uploadToken").asString())).andExpect(status().isUnauthorized());
        jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id) VALUES(1,900,7,1)");
        mvc.perform(multipart(upload).file(new org.springframework.mock.web.MockMultipartFile("file","test","text/csv",csv)).header("Authorization","Upload "+data.path("uploadToken").asString())).andExpect(status().isCreated());
        String attachment=new tools.jackson.databind.json.JsonMapper().readTree(adminCommand("/admin/files/uploads/"+data.path("uploadId").asString()+"/complete",admin,"{}","roster-complete").andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("data").path("attachmentId").asString();
        context.getBean("fileScanWorker",Runnable.class).run();
        adminCommand("/admin/student-roster/imports",admin,"{\"attachmentId\":\""+attachment+"\"}","roster-import").andExpect(status().isAccepted());
        context.getBean("studentRosterWorker",Runnable.class).run();
        Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM student_roster",Integer.class));
        Assertions.assertEquals("succeeded",jdbc.queryForObject("SELECT status FROM async_jobs WHERE job_type='student_roster'",String.class));
        mvc.perform(get("/api/v1/admin/files/"+attachment).header("Authorization",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.studentNumber").doesNotExist());
        adminCommand("/admin/files/"+attachment+"/download-url",admin,"{}","roster-download").andExpect(status().isOk());
        Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action='file.roster.read'",Integer.class));
        mvc.perform(get("/api/v1/files/"+attachment).header("Authorization","Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken())).andExpect(status().isNotFound());
        Assertions.assertThrows(com.caizhitong.common.BusinessException.class,()->context.getBean(com.caizhitong.identity.IdentityFiles.class).roster(new Actor(900,2,Set.of("identity.import"),false,false,"active",SessionKind.ADMIN),Long.parseLong(attachment),10*1024*1024));
    }
    String uploaded(String bearer,String purpose,String mime,byte[] bytes)throws Exception {
        var data=intent(bearer,purpose,mime,bytes);String upload="/api/v1/files/uploads/"+data.path("uploadId").asString();
        mvc.perform(multipart(upload).file(new org.springframework.mock.web.MockMultipartFile("file","test",mime,bytes)).header("Authorization","Upload "+data.path("uploadToken").asString())).andExpect(status().isCreated());
        String result=command(upload.substring(7)+"/complete",bearer,"{}",UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.json.JsonMapper().readTree(result).path("data").path("attachmentId").asString();
    }
    org.springframework.test.web.servlet.ResultActions adminCommand(String path,String bearer,String body,String key)throws Exception {
        var bootstrap=mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        String csrf=new tools.jackson.databind.json.JsonMapper().readTree(bootstrap.getResponse().getContentAsString()).path("data").path("token").asString();
        return mvc.perform(post("/api/v1"+path).secure(true).cookie(bootstrap.getResponse().getCookie("ADMIN-XSRF-TOKEN")).header("X-ADMIN-CSRF",csrf).header("Authorization",bearer).header("Idempotency-Key",key).contentType("application/json").content(body));
    }
    static class TestScanner implements com.caizhitong.integration.scanner.VirusScanner {
        com.caizhitong.integration.scanner.ScanResult result=com.caizhitong.integration.scanner.ScanResult.CLEAN;byte[] original;
        public com.caizhitong.integration.scanner.ScanResult scan(java.io.InputStream stream,long max){Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());try{original=stream.readAllBytes();}catch(java.io.IOException e){throw new RuntimeException(e);}return result;}
    }
    @Test void cleanupWorkerDeletesOnlyDueOrphansAndRetriesFailure()throws Exception {
        Assertions.assertTrue(context.containsBean("fileCleanupWorker"),"Reliable cleanup worker must process persisted deletion jobs");
        String bearer="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();
        var data=intent(bearer,"avatar","image/png",new byte[]{1,2,3});
        mvc.perform(multipart("/api/v1/files/uploads/"+data.path("uploadId").asString()).file(new org.springframework.mock.web.MockMultipartFile("file","test","image/png",new byte[]{1,2,3})).header("Authorization","Upload "+data.path("uploadToken").asString())).andExpect(status().isCreated());
        Runnable worker=context.getBean("fileCleanupWorker",Runnable.class);worker.run();
        Assertions.assertEquals("pending",jdbc.queryForObject("SELECT state FROM file_cleanup_jobs",String.class));
        jdbc.update("UPDATE file_cleanup_jobs SET next_retry_at=TIMESTAMP '2000-01-01 00:00:00'");worker.run();
        Assertions.assertEquals("succeeded",jdbc.queryForObject("SELECT state FROM file_cleanup_jobs",String.class));
    }
    @Test void digestMismatchCannotCompleteAndCleanupRetriesStorageFailure()throws Exception {
        String bearer="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();
        String body="{\"purpose\":\"avatar\",\"fileName\":\"test\",\"mimeType\":\"image/png\",\"sizeBytes\":3,\"sha256\":\""+"0".repeat(64)+"\"}";
        var data=new tools.jackson.databind.json.JsonMapper().readTree(mvc.perform(post("/api/v1/files/upload-intents").header("Authorization",bearer).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        String upload="/api/v1/files/uploads/"+data.path("uploadId").asString();
        mvc.perform(multipart(upload).file(new org.springframework.mock.web.MockMultipartFile("file","test","image/png",new byte[]{1,2,3})).header("Authorization","Upload "+data.path("uploadToken").asString())).andExpect(status().isBadRequest());
        command(upload.substring(7)+"/complete",bearer,"{}","wrong-digest-complete").andExpect(status().isConflict());
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM file_attachments",Integer.class));
        jdbc.update("UPDATE file_cleanup_jobs SET next_retry_at=TIMESTAMP '2000-01-01 00:00:00'");storage.failDelete=true;context.getBean("fileCleanupWorker",Runnable.class).run();
        Assertions.assertEquals("pending",jdbc.queryForObject("SELECT state FROM file_cleanup_jobs",String.class));Assertions.assertEquals(1,jdbc.queryForObject("SELECT retry_count FROM file_cleanup_jobs",Integer.class));
        jdbc.update("UPDATE file_cleanup_jobs SET next_retry_at=TIMESTAMP '2000-01-01 00:00:00'");storage.failDelete=false;context.getBean("fileCleanupWorker",Runnable.class).run();Assertions.assertEquals("succeeded",jdbc.queryForObject("SELECT state FROM file_cleanup_jobs",String.class));
    }
    @Test void onlyOneConcurrentUploadClaimSucceedsAndExpiredTokenIsRejected()throws Exception {
        String bearer="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();var data=intent(bearer,"avatar","image/png",new byte[]{1,2,3});
        String upload="/api/v1/files/uploads/"+data.path("uploadId").asString(),authorization="Upload "+data.path("uploadToken").asString();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<Integer> request=()->mvc.perform(multipart(upload).file(new org.springframework.mock.web.MockMultipartFile("file","test","image/png",new byte[]{1,2,3})).header("Authorization",authorization)).andReturn().getResponse().getStatus();
            var results=pool.invokeAll(List.of(request,request)).stream().map(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).sorted().toList();Assertions.assertEquals(List.of(201,401),results);
        }
        var expired=intent(bearer,"avatar","image/png",new byte[]{1,2,3});jdbc.update("UPDATE file_uploads SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id=?",Long.parseLong(expired.path("uploadId").asString()));
        mvc.perform(multipart("/api/v1/files/uploads/"+expired.path("uploadId").asString()).file(new org.springframework.mock.web.MockMultipartFile("file","test","image/png",new byte[]{1,2,3})).header("Authorization","Upload "+expired.path("uploadToken").asString())).andExpect(status().isUnauthorized());
    }
    @Test void binaryUploadRejectsMultiplePartsMimeAndSizeMismatch()throws Exception {
        String bearer="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();
        for(int mode=0;mode<3;mode++){
            var data=intent(bearer,"avatar","image/png",new byte[]{1,2,3});String upload="/api/v1/files/uploads/"+data.path("uploadId").asString();
            var request=multipart(upload).file(new org.springframework.mock.web.MockMultipartFile("file","test",mode==1?"image/jpeg":"image/png",mode==2?new byte[]{1,2,3,4}:new byte[]{1,2,3})).header("Authorization","Upload "+data.path("uploadToken").asString());
            if(mode==0)request.file(new org.springframework.mock.web.MockMultipartFile("file","extra","image/png",new byte[]{4,5,6}));
            mvc.perform(request).andExpect(mode==2?status().isPayloadTooLarge():status().isBadRequest());
        }
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM file_uploads WHERE state='uploaded'",Integer.class));
    }
    @Test void changedUploadOwnershipIsDeniedBeforeCachedCompleteReplay()throws Exception {
        String bearer="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();String id=uploaded(bearer,"avatar","image/png",new byte[]{1,2,3});
        long upload=jdbc.queryForObject("SELECT upload_id FROM file_attachments WHERE id=?",Long.class,Long.parseLong(id));
        command("/files/uploads/"+upload+"/complete",bearer,"{}","complete-owner-replay").andExpect(status().isAccepted());
        jdbc.update("UPDATE file_uploads SET owner_user_id=102 WHERE id=?",upload);
        command("/files/uploads/"+upload+"/complete",bearer,"{}","complete-owner-replay").andExpect(status().isNotFound());
    }
    static class TestStorage implements com.caizhitong.integration.storage.ObjectStorage {
        final Map<String,byte[]> objects=Collections.synchronizedMap(new HashMap<>());boolean failDelete;
        public void put(String key,java.io.InputStream stream,String mime){Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());try{objects.put(key,stream.readAllBytes());}catch(java.io.IOException e){throw new RuntimeException(e);}}
        public java.io.InputStream open(String key){Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return new java.io.ByteArrayInputStream(objects.get(key));}
        public void delete(String key){Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());if(failDelete)throw FilePurpose.unavailable();objects.remove(key);}
        public String sign(String key,java.time.Duration ttl){Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());Assertions.assertEquals(120,ttl.getSeconds());return "https://objects.test/private?signature="+UUID.randomUUID();}
    }
    @TestConfiguration static class Fixtures {
        @Bean @Primary TestScanner scanner(){return new TestScanner();}
        @Bean @Primary TestStorage storage(){return new TestStorage();}
        @Bean @Primary com.caizhitong.common.ratelimit.RateCounter counter(){return (bucket,window)->1L;}
        @Bean @Primary SessionStore store(){return new SessionStore(){
            final Map<String,SessionRecord> data=new HashMap<>();
            public synchronized void create(SessionRecord r){data.put(r.accessDigest(),r);data.put(r.refreshDigest(),r);}
            public synchronized Optional<SessionRecord> find(String d,SessionKind k){return Optional.ofNullable(data.get(d)).filter(r->r.kind()==k);}
            public synchronized boolean rotate(SessionRecord old,SessionRecord next){if(!data.containsKey(old.refreshDigest()))return false;revoke(old);create(next);return true;}
            public synchronized boolean revoke(SessionRecord r){return data.remove(r.accessDigest())!=null & data.remove(r.refreshDigest())!=null;}
        };}
    }
}
