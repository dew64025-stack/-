package com.caizhitong.community;

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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:community_http;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE) @ActiveProfiles("test") @Import({CommunityHttpTest.Fixtures.class,H2AnonymousOwnership.class})
@Sql(scripts={"/identity-schema.sql","/community-schema.sql"},executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class CommunityHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokenService sessions;
    @Autowired com.caizhitong.identity.OneTimeSecrets secrets;
    @Autowired com.caizhitong.common.SensitiveDataCrypto crypto;
    private final JsonMapper mapper=new JsonMapper();
    private String student, admin;

    @BeforeEach void seed(){
        for(String t:List.of("api_idempotency_records","admin_audit_logs","mutual_aid_ratings","borrow_events","borrow_requests","lost_found_matches","mutual_aid_posts","team_members","team_applications","teams","pseudo_identities","community_comments","community_posts","reports","penalties","appeals","moderation_tasks","credit_disputes","blocks","security_investigations"))jdbc.update("DELETE FROM "+t);
        jdbc.update("DELETE FROM user_roles");jdbc.update("DELETE FROM roles");
        jdbc.update("DELETE FROM user_phones");jdbc.update("DELETE FROM authorizations");jdbc.update("DELETE FROM student_verifications");
        jdbc.update("DELETE FROM users");jdbc.update("DELETE FROM campuses");
        jdbc.update("INSERT INTO campuses VALUES(1,'大学','one','active',NULL)");
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(101,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(102,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(900,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        for(long uid:new long[]{101L,102L}){
            jdbc.update("INSERT INTO user_phones(id,user_id,phone_encrypted,phone_search,verified,created_at) VALUES(?,?,?,?,1,CURRENT_TIMESTAMP)",uid,uid,new byte[]{1},"ph"+uid);
            jdbc.update("INSERT INTO authorizations(id,user_id,type,scope,status,version,granted_at,revoked_at,created_at) VALUES(?,?,'phone','*','granted',0,CURRENT_TIMESTAMP,NULL,CURRENT_TIMESTAMP)",uid+1000,uid);
            jdbc.update("INSERT INTO student_verifications(id,user_id,campus_id,student_id_encrypted,student_id_search,status,version,created_at,updated_at) VALUES(?,?,1,?,?,'verified',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",uid+2000,uid,new byte[]{1},"sv"+uid);
        }
        jdbc.update("INSERT INTO roles(id,code) VALUES(7,'campus_admin')");jdbc.update("INSERT INTO roles(id,code) VALUES(9,'security_investigator')");
        jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id) VALUES(1,900,7,1)");
        student="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();
        admin="Bearer "+sessions.issue(900,1,SessionKind.ADMIN).accessToken();
    }

    @Test void mutualAidPendingThenApprovedBecomesPublic() throws Exception {
        JsonNode created=mapper.readTree(command("/mutual-aid/posts",student,"{\"category\":\"secondhand\",\"title\":\"转让教材\",\"price\":\"12.50\"}","ma-create").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
        String id=created.path("id").asString();
        assertEquals("pending",created.path("status").asString());
        // pending 不公开
        mvc.perform(get("/api/v1/mutual-aid/posts?campusCode=one")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        // 审核通过
        long task=jdbc.queryForObject("SELECT id FROM moderation_tasks WHERE target_id=?",Long.class,Long.parseLong(id));
        adminCommand("/admin/moderation-tasks/"+task+"/claim",admin,"{}","ma-claim").andExpect(status().isOk());
        adminCommand("/admin/moderation-tasks/"+task+"/decision",admin,"{\"decision\":\"approve\",\"reason\":\"ok\"}","ma-approve").andExpect(status().isOk());
        assertEquals("published",jdbc.queryForObject("SELECT status FROM mutual_aid_posts WHERE id=?",String.class,Long.parseLong(id)));
        mvc.perform(get("/api/v1/mutual-aid/posts?campusCode=one")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test void teamApplyAndApproveAddsMember() throws Exception {
        String team=mapper.readTree(command("/teams",student,"{\"title\":\"数学建模队\",\"openRoles\":[{\"role\":\"前端\",\"skillTags\":[\"vue\"],\"quantity\":2}]}","team-create").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id=?",Integer.class,Long.parseLong(team)));
        String bearer2="Bearer "+sessions.issue(102,1,SessionKind.STUDENT).accessToken();
        String app=mapper.readTree(command("/team-applications",bearer2,"{\"teamId\":\""+team+"\"}","team-apply").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        command("/team-applications/"+app+"/decision",student,"{\"decision\":\"approve\"}","team-approve").andExpect(status().isOk());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id=?",Integer.class,Long.parseLong(team)));
    }

    @Test void communityPostIsAnonymousAndAuthorCanEdit() throws Exception {
        JsonNode post=mapper.readTree(command("/community/posts",student,"{\"title\":\"求安慰\",\"content\":\"今天有点难过\"}","com-create").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
        String id=post.path("id").asString();
        assertEquals("pending",post.path("status").asString());
        assertFalse(post.has("userId"));
        assertFalse(post.has("pseudoIdentityId"));
        // 作者能看 pending
        mvc.perform(get("/api/v1/community/posts/"+id).header("Authorization",student)).andExpect(status().isOk());
        // 他人看不到 pending
        String other="Bearer "+sessions.issue(102,1,SessionKind.STUDENT).accessToken();
        mvc.perform(get("/api/v1/community/posts/"+id).header("Authorization",other)).andExpect(status().isNotFound());
        // 审核发布
        long task=jdbc.queryForObject("SELECT id FROM moderation_tasks WHERE target_id=?",Long.class,Long.parseLong(id));
        adminCommand("/admin/moderation-tasks/"+task+"/claim",admin,"{}","com-claim").andExpect(status().isOk());
        adminCommand("/admin/moderation-tasks/"+task+"/decision",admin,"{\"decision\":\"approve\",\"reason\":\"ok\"}","com-approve").andExpect(status().isOk());
        mvc.perform(get("/api/v1/community/posts").header("Authorization",other)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1)).andExpect(jsonPath("$.data.items[0].userId").doesNotExist());
        // 映射加密，无明文 user_id
        assertNull(jdbc.queryForObject("SELECT user_id FROM pseudo_identities WHERE post_id=?",Long.class,Long.parseLong(id)));
        assertNotNull(jdbc.queryForObject("SELECT user_id_encrypted FROM pseudo_identities WHERE post_id=?",byte[].class,Long.parseLong(id)));
    }

    @Test void reportPenaltyAppealCloses() throws Exception {
        // 发一个已发布帖子用于举报
        JsonNode post=mapper.readTree(command("/community/posts",student,"{\"title\":\"x\",\"content\":\"y\"}","rp-post-create").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
        long pid=Long.parseLong(post.path("id").asString());
        jdbc.update("UPDATE community_posts SET status='published' WHERE id=?",pid);
        // 举报
        String report=mapper.readTree(command("/reports",student,"{\"targetType\":\"community_post\",\"targetId\":\""+pid+"\",\"reason\":\"违规\"}","rp-create").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        // 处罚
        String penalty=mapper.readTree(adminCommand("/admin/penalties",admin,"{\"userId\":\"101\",\"type\":\"mute\",\"reason\":\"违规发言\"}","rp-penalty").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM penalties WHERE id=?",Integer.class,Long.parseLong(penalty)));
        // 申诉
        command("/appeals",student,"{\"penaltyId\":\""+penalty+"\",\"reason\":\"误判\"}","rp-appeal").andExpect(status().isCreated());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM appeals WHERE penalty_id=?",Integer.class,Long.parseLong(penalty)));
    }

    @Test void blockUnblockIsIdempotent() throws Exception {
        mvc.perform(put("/api/v1/blocks/community_post/999").header("Authorization",student).header("Idempotency-Key","block-key-1").contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.blocked").value(true));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM blocks WHERE blocker_id=101",Integer.class));
        mvc.perform(delete("/api/v1/blocks/community_post/999").header("Authorization",student).header("Idempotency-Key","unblock-key-1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.blocked").value(false));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM blocks WHERE blocker_id=101",Integer.class));
    }

    @Test void moderationDecisionMapsStatusPerTargetType() throws Exception {
        long post1=Long.parseLong(mapper.readTree(command("/mutual-aid/posts",student,"{\"category\":\"secondhand\",\"title\":\"A\"}","md-post1").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString());
        long task1=jdbc.queryForObject("SELECT id FROM moderation_tasks WHERE target_id=?",Long.class,post1);
        adminCommand("/admin/moderation-tasks/"+task1+"/claim",admin,"{}","md-claim1").andExpect(status().isOk());
        adminCommand("/admin/moderation-tasks/"+task1+"/decision",admin,"{\"decision\":\"restrict\",\"reason\":\"x\"}","md-decision1").andExpect(status().isOk());
        assertEquals("rejected",jdbc.queryForObject("SELECT status FROM mutual_aid_posts WHERE id=?",String.class,post1));
        long post2=Long.parseLong(mapper.readTree(command("/community/posts",student,"{\"title\":\"B\",\"content\":\"b\"}","md-post2").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString());
        jdbc.update("UPDATE community_posts SET status='published' WHERE id=?",post2);
        long comment=Long.parseLong(mapper.readTree(command("/community/posts/"+post2+"/comments",student,"{\"content\":\"c\"}","md-comment").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString());
        long task2=jdbc.queryForObject("SELECT id FROM moderation_tasks WHERE target_id=?",Long.class,comment);
        adminCommand("/admin/moderation-tasks/"+task2+"/claim",admin,"{}","md-claim2").andExpect(status().isOk());
        adminCommand("/admin/moderation-tasks/"+task2+"/decision",admin,"{\"decision\":\"reject\",\"reason\":\"x\"}","md-decision2").andExpect(status().isOk());
        assertEquals("deleted",jdbc.queryForObject("SELECT status FROM community_comments WHERE id=?",String.class,comment));
    }

    @Test void ratingsInheritPostVisibilityForVisitorsOwnersAndOtherCampuses() throws Exception {
        long pid=Long.parseLong(mapper.readTree(command("/mutual-aid/posts",student,"{\"category\":\"secondhand\",\"title\":\"private\"}","privacy-ratings").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString());
        jdbc.update("INSERT INTO mutual_aid_ratings VALUES(1,?,102,5,'synthetic rating',CURRENT_TIMESTAMP)",pid);
        String other="Bearer "+sessions.issue(102,1,SessionKind.STUDENT).accessToken();
        mvc.perform(get("/api/v1/mutual-aid/posts/"+pid+"/ratings?campusCode=one")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/mutual-aid/posts/"+pid+"/ratings").header("Authorization",other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/mutual-aid/posts/"+pid+"/ratings").header("Authorization",student)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        jdbc.update("UPDATE mutual_aid_posts SET status='published' WHERE id=?",pid);
        mvc.perform(get("/api/v1/mutual-aid/posts/"+pid+"/ratings?campusCode=one")).andExpect(status().isOk());
        jdbc.update("INSERT INTO campuses VALUES(2,'other','two','active',NULL)");
        mvc.perform(get("/api/v1/mutual-aid/posts/"+pid+"/ratings?campusCode=two")).andExpect(status().isNotFound());
        jdbc.update("UPDATE mutual_aid_posts SET status='deleted',deleted_at=CURRENT_TIMESTAMP WHERE id=?",pid);
        mvc.perform(get("/api/v1/mutual-aid/posts/"+pid+"/ratings?campusCode=one")).andExpect(status().isNotFound());
    }

    @Test void privateProofNeverLeavesPostDtoAndRemainsStored() throws Exception {
        String body=command("/mutual-aid/posts",student,"{\"category\":\"lost_found\",\"title\":\"synthetic\",\"detail\":{\"privateProofHint\":\"synthetic-private-proof\",\"kind\":\"lost\"}}","privacy-proof").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String pid=mapper.readTree(body).path("data").path("id").asString();
        assertFalse(body.contains("privateProofHint"));
        jdbc.update("UPDATE mutual_aid_posts SET status='published' WHERE id=?",Long.parseLong(pid));
        for(String path:List.of("/mutual-aid/posts?campusCode=one","/mutual-aid/posts/"+pid+"?campusCode=one","/me/mutual-aid/posts")){
            String json=mvc.perform(get("/api/v1"+path).header("Authorization",student)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertFalse(json.contains("privateProofHint"),path);
            assertFalse(json.contains("synthetic-private-proof"),path);
        }
        assertTrue(jdbc.queryForObject("SELECT detail_json FROM mutual_aid_posts WHERE id=?",String.class,Long.parseLong(pid)).contains("synthetic-private-proof"));
    }

    @Test void communityReadsRequireVerifiedStudent() throws Exception {
        long pid=createPublishedCommunityPost();
        jdbc.update("UPDATE student_verifications SET status='pending' WHERE user_id=101");
        for(String path:List.of("/community/posts","/community/posts/"+pid,"/community/posts/"+pid+"/comments"))
            mvc.perform(get("/api/v1"+path).header("Authorization",student)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/community/posts").header("Authorization",student)).andExpect(status().isOk());
    }

    @Test void commentsRespectRestrictedAndDeletedParentsAndExposeOnlyOwnerCapability() throws Exception {
        long pid=createPublishedCommunityPost();
        String created=command("/community/posts/"+pid+"/comments",student,"{\"content\":\"synthetic\"}","privacy-comment").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode comment=mapper.readTree(created).path("data");
        assertEquals(Long.toString(pid),comment.path("postId").asString());
        assertTrue(comment.path("canDelete").asBoolean());
        assertFalse(created.contains("userId"));
        jdbc.update("UPDATE community_comments SET status='published' WHERE post_id=?",pid);
        String other="Bearer "+sessions.issue(102,1,SessionKind.STUDENT).accessToken();
        mvc.perform(get("/api/v1/community/posts/"+pid+"/comments").header("Authorization",other)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].canDelete").value(false));
        mvc.perform(get("/api/v1/community/posts/"+pid+"/comments").header("Authorization",student)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].canDelete").value(true));
        for(String state:List.of("restricted","deleted")){
            jdbc.update("UPDATE community_posts SET status=? WHERE id=?",state,pid);
            mvc.perform(get("/api/v1/community/posts/"+pid+"/comments").header("Authorization",other)).andExpect(status().isNotFound());
            command("/community/posts/"+pid+"/comments",student,"{\"content\":\"no\"}","privacy-comment-"+state).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/community/posts/"+pid).header("Authorization",student)).andExpect(status().isNotFound());
    }

    @Test void blockingAcceptsDocumentedEmptyBody() throws Exception {
        mvc.perform(put("/api/v1/blocks/community_post/999").header("Authorization",student).header("Idempotency-Key","block-no-body")).andExpect(status().isOk()).andExpect(jsonPath("$.data.blocked").value(true));
    }

    @Test void campusAdminCannotPenalizeOrLiftAnotherCampusUser() throws Exception {
        seedOtherCampusGovernance();
        adminCommand("/admin/penalties",admin,"{\"userId\":\"201\",\"type\":\"mute\",\"reason\":\"Synthetic\"}","cross-penalty").andExpect(status().isNotFound());
        adminCommand("/admin/penalties/801/lift",admin,"{\"reason\":\"Synthetic\"}","cross-lift").andExpect(status().isNotFound());
        assertEquals("active",jdbc.queryForObject("SELECT status FROM penalties WHERE id=801",String.class));
    }

    @Test void campusAdminCannotListOrDecideAnotherCampusAppeal() throws Exception {
        seedOtherCampusGovernance();
        mvc.perform(get("/api/v1/admin/appeals").header("Authorization",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        adminCommand("/admin/appeals/802/decision",admin,"{\"decision\":\"approve\",\"reason\":\"Synthetic\"}","cross-appeal").andExpect(status().isNotFound());
        assertEquals("pending",jdbc.queryForObject("SELECT status FROM appeals WHERE id=802",String.class));
    }

    @Test void sameCampusGovernancePreservesReasonsAndAudit() throws Exception {
        long penalty=Long.parseLong(mapper.readTree(adminCommand("/admin/penalties",admin,"{\"userId\":\"101\",\"type\":\"mute\",\"reason\":\"Synthetic penalty reason\"}","audited-penalty").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString());
        adminCommand("/admin/penalties/"+penalty+"/lift",admin,"{\"reason\":\"Synthetic lift reason\"}","audited-lift").andExpect(status().isOk());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE target_id=? AND reason IN ('Synthetic penalty reason','Synthetic lift reason')",Integer.class,penalty));
        adminCommand("/admin/penalties/"+penalty+"/lift",admin,"{\"reason\":\"again\"}","audited-lift-again").andExpect(status().isConflict());
    }

    @Test void crossCampusPenaltyLiftAndAppealDecisionAreHidden() throws Exception {
        seedOtherCampusGovernance();
        assertAll(
            ()->adminCommand("/admin/penalties/801/lift",admin,"{\"reason\":\"Synthetic\"}","cross-lift-only").andExpect(status().isNotFound()),
            ()->adminCommand("/admin/appeals/802/decision",admin,"{\"decision\":\"approve\",\"reason\":\"Synthetic\"}","cross-appeal-only").andExpect(status().isNotFound()));
    }

    private void seedOtherCampusGovernance(){
        jdbc.update("INSERT INTO campuses VALUES(2,'other','two','active',NULL)");
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(201,2,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO penalties(id,user_id,penalty_type,reason,status,created_by,created_at) VALUES(801,201,'mute','Synthetic','active',900,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO appeals(id,user_id,penalty_id,reason,status,created_at) VALUES(802,201,801,'Synthetic','pending',CURRENT_TIMESTAMP)");
    }

    @Test void investigationWithoutDedicatedConfigurationFailsClosed() throws Exception {
        long pid=createPublishedCommunityPost();
        jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id) VALUES(2,900,9,1)");
        var csrf=mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andReturn().getResponse();
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        secrets.claim("admin-reauth",HexFormat.of().formatHex(crypto.searchHash("admin-reauth",token)),"900:1:"+(java.time.Instant.now().getEpochSecond()+300),java.time.Duration.ofMinutes(5));
        mvc.perform(post("/api/v1/admin/security-investigations").secure(true).cookie(csrf.getCookie("ADMIN-XSRF-TOKEN"))
            .header("X-ADMIN-CSRF",mapper.readTree(csrf.getContentAsString()).path("data").path("token").asString()).header("Authorization",admin)
            .header("Idempotency-Key","investigation-disabled").header("X-Reauth-Token",token).contentType("application/json")
            .content("{\"targetType\":\"community_post\",\"targetId\":\""+pid+"\",\"reason\":\"Synthetic investigation\"}"))
            .andExpect(status().isServiceUnavailable());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM security_investigations",Integer.class));
    }

    @Test void commentsCannotReadRestrictedParent() throws Exception {
        long pid=createPublishedCommunityPost();
        jdbc.update("UPDATE community_posts SET status='restricted' WHERE id=?",pid);
        String other="Bearer "+sessions.issue(102,1,SessionKind.STUDENT).accessToken();
        mvc.perform(get("/api/v1/community/posts/"+pid+"/comments").header("Authorization",other)).andExpect(status().isNotFound());
    }

    @Test void deletedParentCannotBeReadEvenByAuthor() throws Exception {
        long pid=createPublishedCommunityPost();
        jdbc.update("UPDATE community_posts SET status='deleted',deleted_at=CURRENT_TIMESTAMP WHERE id=?",pid);
        mvc.perform(get("/api/v1/community/posts/"+pid).header("Authorization",student)).andExpect(status().isNotFound());
    }

    private long createPublishedCommunityPost() throws Exception {
        long pid=Long.parseLong(mapper.readTree(command("/community/posts",student,"{\"title\":\"synthetic\",\"content\":\"synthetic\"}","privacy-post").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString());
        jdbc.update("UPDATE community_posts SET status='published' WHERE id=?",pid);
        return pid;
    }

    private ResultActions command(String path,String bearer,String body,String key) throws Exception {
        return mvc.perform(post("/api/v1"+path).header("Authorization",bearer).header("Idempotency-Key",key).contentType("application/json").content(body));
    }
    private ResultActions adminCommand(String path,String bearer,String body,String key) throws Exception {
        var bootstrap=mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        String csrf=mapper.readTree(bootstrap.getResponse().getContentAsString()).path("data").path("token").asString();
        return mvc.perform(post("/api/v1"+path).secure(true).cookie(bootstrap.getResponse().getCookie("ADMIN-XSRF-TOKEN")).header("X-ADMIN-CSRF",csrf).header("Authorization",bearer).header("Idempotency-Key",key).contentType("application/json").content(body));
    }
    @TestConfiguration static class Fixtures {
        @Bean @Primary com.caizhitong.identity.OneTimeSecrets testSecrets(){return new com.caizhitong.identity.OneTimeSecrets(){
            final Map<String,String> data=new HashMap<>();
            public synchronized boolean claim(String namespace,String digest,String value,java.time.Duration ttl){return data.putIfAbsent(namespace+":"+digest,value)==null;}
            public synchronized String consume(String namespace,String digest){return data.remove(namespace+":"+digest);}
        };}
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
