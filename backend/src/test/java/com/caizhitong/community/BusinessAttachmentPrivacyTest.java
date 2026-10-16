package com.caizhitong.community;

import com.caizhitong.common.*;
import com.caizhitong.material.files.AttachmentService;
import com.caizhitong.security.*;
import com.caizhitong.integration.storage.ObjectStorage;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:business_attachment;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("test")
@Import({CommunityHttpTest.Fixtures.class,H2AnonymousOwnership.class,BusinessAttachmentPrivacyTest.StorageFixture.class})
@Sql(scripts={"/identity-schema.sql","/files-schema.sql","/community-schema.sql","/service-schema.sql"},executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class BusinessAttachmentPrivacyTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AttachmentService files;
    @Autowired ActorLoader actors;
    @Autowired SensitiveDataCrypto crypto;
    @Autowired SessionTokenService sessions;
    @Autowired MockMvc mvc;
    @BeforeEach void seed(){
        for(String table:List.of("file_attachments","mutual_aid_posts","campus_issues","work_orders","user_roles","roles","users","campuses"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO campuses VALUES(1,'Synthetic','one','active',NULL),(2,'Other','two','active',NULL)");
        for(int id:new int[]{101,102,201,900,901})jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(?,?,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,id==201?2:1);
        jdbc.update("INSERT INTO roles(id,code) VALUES(7,'campus_admin')");jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id) VALUES(1,900,7,1)");
        jdbc.update("INSERT INTO roles(id,code) VALUES(8,'business_operator')");jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id) VALUES(2,901,8,1)");
        attachment(501,101,"student","mutual_aid");attachment(502,101,"student","issue");attachment(503,101,"student","workorder");
        // Historical trusted import: tests the read boundary, not a new administrator upload capability.
        attachment(504,900,"admin","mutual_aid");
        jdbc.update("INSERT INTO mutual_aid_posts(id,campus_id,user_id,category,title,status,images_json,created_at) VALUES(601,1,101,'secondhand','Synthetic','published','[501,504]',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO campus_issues(id,campus_id,user_id,title,status,visibility,images_json,created_at,updated_at,source_type) VALUES(602,1,101,'Synthetic','published','public','[502]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'manual')");
        jdbc.update("INSERT INTO work_orders(id,campus_id,user_id,status,images_json,created_at,updated_at,source_type) VALUES(603,1,101,'pending','[503]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'manual')");
    }
    @Test void publishedMutualAllowsSameCampusAndExplicitPublicReadIncludingImportedAdminDomain(){
        assertAll(()->assertNotNull(files.download(actor(101),501)),()->assertNotNull(files.download(actor(102),501)),
            ()->assertNotNull(files.download(null,501)),()->assertNotNull(files.download(actor(102),504)),
            ()->assertEquals(404,assertThrows(BusinessException.class,()->files.download(actor(201),501)).getStatus()));
        jdbc.update("UPDATE mutual_aid_posts SET status='pending' WHERE id=601");
        assertEquals(404,assertThrows(BusinessException.class,()->files.download(actor(102),501)).getStatus());
        assertEquals(404,assertThrows(BusinessException.class,()->files.download(null,504)).getStatus());
    }
    @Test void issueAndWorkorderFollowCurrentParentAndPurpose(){
        assertAll(()->assertNotNull(files.download(actor(102),502)),()->assertNotNull(files.download(null,502)),
            ()->assertNotNull(files.download(actor(900),503)),()->assertNotNull(files.download(actor(101),503)),
            ()->assertEquals(404,assertThrows(BusinessException.class,()->files.download(actor(102),503)).getStatus()),
            ()->assertEquals(404,assertThrows(BusinessException.class,()->files.download(null,503)).getStatus()));
        jdbc.update("UPDATE campus_issues SET visibility='private',status='hidden' WHERE id=602");
        assertEquals(404,assertThrows(BusinessException.class,()->files.download(actor(102),502)).getStatus());
        assertNotNull(files.download(actor(900),502));
        jdbc.update("UPDATE work_orders SET deleted_at=CURRENT_TIMESTAMP WHERE id=603");
        assertEquals(404,assertThrows(BusinessException.class,()->files.download(actor(900),503)).getStatus());
    }
    @Test void privatePurposesUnlinkedFilesAndCrossCampusLinksNeverGrantRead(){
        for(String purpose:List.of("material","avatar","student_roster","notice_document","message","community")){
            jdbc.update("UPDATE file_attachments SET purpose=? WHERE id=501",purpose);
            assertEquals(404,assertThrows(BusinessException.class,()->files.download(actor(102),501)).getStatus(),purpose);
            assertEquals(404,assertThrows(BusinessException.class,()->files.download(null,501)).getStatus(),purpose);
        }
        jdbc.update("UPDATE file_attachments SET purpose='mutual_aid',campus_id=2 WHERE id=501");
        assertEquals(404,assertThrows(BusinessException.class,()->files.download(null,501)).getStatus());
        jdbc.update("UPDATE mutual_aid_posts SET images_json='[5040]' WHERE id=601");
        assertEquals(404,assertThrows(BusinessException.class,()->files.download(actor(102),504)).getStatus());
    }
    @Test void publicBusinessDtoHasAuthorizedShortLivedImageButGenericFileRouteStaysAuthenticated()throws Exception {
        mvc.perform(get("/api/v1/mutual-aid/posts/601?campusCode=one")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.post.images[0].id").value("501"))
            .andExpect(jsonPath("$.data.post.images[0].thumbnailUrl").value("https://synthetic.invalid/image"))
            .andExpect(jsonPath("$.data.post.images[0].urlExpiresAt").isNotEmpty())
            .andExpect(jsonPath("$.data.post.images[0].fileName").doesNotExist());
        mvc.perform(get("/api/v1/files/501")).andExpect(status().isUnauthorized());
    }
    @Test void businessOperatorSeesOnlyAuthorizedBusinessImagesWithoutGenericFileRead()throws Exception {
        assertFalse(actor(901).permissions().contains("file.read"));
        String token="Bearer "+sessions.issue(901,1,SessionKind.ADMIN).accessToken();
        mvc.perform(get("/api/v1/admin/work-orders").header("Authorization",token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].images[0].thumbnailUrl").value("https://synthetic.invalid/image"));
        mvc.perform(get("/api/v1/admin/campus-issues").header("Authorization",token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].images[0].thumbnailUrl").value("https://synthetic.invalid/image"));
        mvc.perform(get("/api/v1/admin/files/503").header("Authorization",token)).andExpect(status().isForbidden());
        jdbc.update("UPDATE work_orders SET campus_id=2 WHERE id=603");
        mvc.perform(get("/api/v1/admin/work-orders").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }
    private Actor actor(long id){return actors.load(id,id==201?2:1,id>=900?SessionKind.ADMIN:SessionKind.STUDENT).orElseThrow();}
    @Test void businessPreviewCannotBypassExactLinkPurposeCampusOrCleanState(){
        assertNotNull(files.businessPreview(actor(901),503,1,"workorder",603));
        assertEquals(404,assertThrows(BusinessException.class,()->files.businessPreview(actor(901),503,1,"workorder",604)).getStatus());
        assertEquals(404,assertThrows(BusinessException.class,()->files.businessPreview(actor(901),502,1,"workorder",603)).getStatus());
        assertEquals(404,assertThrows(BusinessException.class,()->files.businessPreview(actor(201),503,1,"workorder",603)).getStatus());
        jdbc.update("UPDATE file_attachments SET purpose='material' WHERE id=503");
        assertEquals(404,assertThrows(BusinessException.class,()->files.businessPreview(actor(901),503,1,"material",603)).getStatus());
        jdbc.update("UPDATE file_attachments SET purpose='workorder',scan_status='pending' WHERE id=503");
        assertEquals(409,assertThrows(BusinessException.class,()->files.businessPreview(actor(901),503,1,"workorder",603)).getStatus());
        jdbc.update("UPDATE file_attachments SET scan_status='clean',state='deleted' WHERE id=503");
        assertEquals(404,assertThrows(BusinessException.class,()->files.businessPreview(actor(901),503,1,"workorder",603)).getStatus());
        jdbc.update("UPDATE file_attachments SET state='active',mime_type='application/pdf' WHERE id=503");
        assertNull(files.businessPreview(actor(901),503,1,"workorder",603));
        jdbc.update("UPDATE work_orders SET deleted_at=CURRENT_TIMESTAMP WHERE id=603");
        assertEquals(404,assertThrows(BusinessException.class,()->files.businessPreview(actor(901),503,1,"workorder",603)).getStatus());
    }
    @Test void publicIssueDoesNotPublishPendingEvidenceAttachments(){
        attachment(505,101,"student","issue");
        jdbc.update("INSERT INTO campus_issue_evidences(id,issue_id,user_id,content,images_json,review_status,created_at) VALUES(605,602,101,'Synthetic private evidence','[505]','pending',CURRENT_TIMESTAMP)");
        assertEquals(404,assertThrows(BusinessException.class,()->files.download(actor(102),505)).getStatus());
        assertEquals(404,assertThrows(BusinessException.class,()->files.download(null,505)).getStatus());
        assertEquals(404,assertThrows(BusinessException.class,()->files.businessPreview(actor(102),505,1,"issue",602)).getStatus());
    }
    private void attachment(long id,long owner,String domain,String purpose){jdbc.update("INSERT INTO file_attachments(id,upload_id,campus_id,owner_user_id,access_domain,purpose,file_name,mime_type,size_bytes,content_sha256,object_key_encrypted,scan_status,state,created_at,updated_at) VALUES(?,?,1,?,?,?,'Synthetic','image/png',3,?,?,'clean','active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,id,owner,domain,purpose,"a".repeat(64),crypto.encrypt("file-object-key","synthetic/"+id));}
    @TestConfiguration static class StorageFixture {
        @Bean @Primary ObjectStorage syntheticStorage(){return new ObjectStorage(){
            public void put(String key,java.io.InputStream body,String mime){throw new UnsupportedOperationException();}
            public java.io.InputStream open(String key){throw new UnsupportedOperationException();}
            public void delete(String key){throw new UnsupportedOperationException();}
            public String sign(String key,java.time.Duration ttl){assertEquals(java.time.Duration.ofSeconds(120),ttl);return "https://synthetic.invalid/image";}
        };}
    }
}
