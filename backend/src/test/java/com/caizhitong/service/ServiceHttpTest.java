package com.caizhitong.service;

import com.caizhitong.identity.OneTimeSecrets;
import com.caizhitong.security.*;
import java.time.Duration;
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

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:service_http;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(ServiceHttpTest.Fixtures.class)
@Sql(scripts={"/identity-schema.sql","/service-schema.sql"},executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ServiceHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokenService sessions;
    private final JsonMapper mapper=new JsonMapper();
    private String student, student2, admin;

    @BeforeEach void seed(){
        for(String t:List.of("api_idempotency_records","work_order_ratings","work_order_events","work_orders","campus_issue_locations","campus_issue_follows","campus_issue_evidences","campus_issue_votes","campus_issues","reservation_checkins","resource_occupancies","reservations","resource_slots","resources","crowd_observations"))jdbc.update("DELETE FROM "+t);
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
        jdbc.update("INSERT INTO roles(id,code) VALUES(7,'campus_admin')");
        jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id) VALUES(1,900,7,1)");
        student="Bearer "+sessions.issue(101,1,SessionKind.STUDENT).accessToken();
        student2="Bearer "+sessions.issue(102,1,SessionKind.STUDENT).accessToken();
        admin="Bearer "+sessions.issue(900,1,SessionKind.ADMIN).accessToken();
    }

    @Test void workOrderLifecycleThroughRating() throws Exception {
        String wo=mapper.readTree(command("/work-orders",student,"{\"category\":\"dorm\",\"description\":\"水龙头坏了\",\"urgency\":\"normal\"}","wo-create").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        assertEquals("pending",jdbc.queryForObject("SELECT status FROM work_orders WHERE id=?",String.class,Long.parseLong(wo)));
        adminCommand("/admin/work-orders/"+wo+"/transition",admin,"{\"toStatus\":\"accepted\",\"version\":0,\"comment\":\"受理\"}","wo-accept").andExpect(status().isOk());
        adminCommand("/admin/work-orders/"+wo+"/transition",admin,"{\"toStatus\":\"processing\",\"version\":1}","wo-process").andExpect(status().isOk());
        adminCommand("/admin/work-orders/"+wo+"/transition",admin,"{\"toStatus\":\"completed\",\"version\":2}","wo-complete").andExpect(status().isOk());
        assertEquals("completed",jdbc.queryForObject("SELECT status FROM work_orders WHERE id=?",String.class,Long.parseLong(wo)));
        command("/work-orders/"+wo+"/ratings",student,"{\"score\":5}","wo-rating").andExpect(status().isCreated());
        assertEquals("rated",jdbc.queryForObject("SELECT status FROM work_orders WHERE id=?",String.class,Long.parseLong(wo)));
    }

    @Test void issuePublishThenVoteAndFollow() throws Exception {
        String issue=mapper.readTree(command("/campus-issues",student,"{\"title\":\"食堂排队\",\"category\":\"canteen\"}","is-create").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        mvc.perform(get("/api/v1/campus-issues?campusCode=one")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        adminCommand("/admin/campus-issues/"+issue+"/decision",admin,"{\"decision\":\"publish\",\"version\":0,\"reason\":\"ok\"}","is-publish").andExpect(status().isOk());
        assertEquals("public",jdbc.queryForObject("SELECT visibility FROM campus_issues WHERE id=?",String.class,Long.parseLong(issue)));
        mvc.perform(get("/api/v1/campus-issues?campusCode=one")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(put("/api/v1/campus-issues/"+issue+"/vote").header("Authorization",student).header("Idempotency-Key","vote-key-1").contentType("application/json").content("{\"value\":1}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.voteCount").value(1));
        command("/campus-issues/"+issue+"/follows",student,"{}","is-follow").andExpect(status().isOk()).andExpect(jsonPath("$.data.followCount").value(1));
    }

    @Test void reservationPreventsDoubleBookingAndReleasesOnCancel() throws Exception {
        String resource=mapper.readTree(adminCommand("/admin/resources",admin,"{\"type\":\"library_seat\",\"name\":\"三楼自习区\",\"capacity\":1}","rs-create").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        String slot=mapper.readTree(adminCommand("/admin/resources/"+resource+"/slots",admin,"{\"slotDate\":\"2026-10-20\",\"startTime\":\"08:00:00\",\"endTime\":\"10:00:00\",\"capacity\":1}","rs-slot-1").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        String resv=mapper.readTree(command("/reservations",student,"{\"slotId\":\""+slot+"\",\"seatNo\":1}","rs-book-1").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE slot_id=?",Integer.class,Long.parseLong(slot)));
        command("/reservations",student2,"{\"slotId\":\""+slot+"\",\"seatNo\":1}","rs-book2").andExpect(status().isConflict());
        command("/reservations/"+resv+"/cancel",student,"{\"version\":0}","rs-cancel").andExpect(status().isOk());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE slot_id=?",Integer.class,Long.parseLong(slot)));
        command("/reservations",student2,"{\"slotId\":\""+slot+"\",\"seatNo\":1}","rs-book3").andExpect(status().isCreated());
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
        @Bean @Primary OneTimeSecrets secrets(){return new OneTimeSecrets(){
            final Map<String,String> data=new HashMap<>();
            public synchronized boolean claim(String ns,String digest,String value,Duration ttl){data.put(ns+":"+digest,value);return true;}
            public synchronized String consume(String ns,String digest){return data.remove(ns+":"+digest);}
        };}
    }
}
