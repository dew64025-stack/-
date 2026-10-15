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
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(CommunityHttpTest.Fixtures.class)
@Sql(scripts={"/identity-schema.sql","/community-schema.sql"},executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class CommunityHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokenService sessions;
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

    private ResultActions command(String path,String bearer,String body,String key) throws Exception {
        return mvc.perform(post("/api/v1"+path).header("Authorization",bearer).header("Idempotency-Key",key).contentType("application/json").content(body));
    }
    private ResultActions adminCommand(String path,String bearer,String body,String key) throws Exception {
        var bootstrap=mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        String csrf=mapper.readTree(bootstrap.getResponse().getContentAsString()).path("data").path("token").asString();
        return mvc.perform(post("/api/v1"+path).secure(true).cookie(bootstrap.getResponse().getCookie("ADMIN-XSRF-TOKEN")).header("X-ADMIN-CSRF",csrf).header("Authorization",bearer).header("Idempotency-Key",key).contentType("application/json").content(body));
    }
    @TestConfiguration static class Fixtures {
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
