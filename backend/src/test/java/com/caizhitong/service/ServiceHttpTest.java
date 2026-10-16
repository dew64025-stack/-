package com.caizhitong.service;

import com.caizhitong.identity.OneTimeSecrets;
import com.caizhitong.security.*;
import java.time.*;
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
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE) @ActiveProfiles("test") @Import(ServiceHttpTest.Fixtures.class)
@Sql(scripts={"/identity-schema.sql","/service-schema.sql"},executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ServiceHttpTest {
    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean JdbcTemplate jdbc;
    @Autowired SessionTokenService sessions;
    @Autowired TestClock clock;
    @Autowired com.caizhitong.resource.ReservationExpiryWorker expiryWorker;
    private final JsonMapper mapper=new JsonMapper();
    private String student, student2, admin;

    @BeforeEach void seed(){
        clock.value=Instant.parse("2026-10-20T00:00:00Z");
        for(String t:List.of("api_idempotency_records","work_order_ratings","work_order_events","work_orders","campus_issue_events","campus_issue_locations","campus_issue_follows","campus_issue_evidences","campus_issue_votes","campus_issues","reservation_checkins","resource_occupancies","reservations","resource_slots","resources","crowd_observations","external_references","external_systems","campus_regions"))jdbc.update("DELETE FROM "+t);
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
        adminCommand("/admin/work-orders/"+wo+"/transition",admin,"{\"toStatus\":\"reviewing\",\"version\":0,\"comment\":\"审核通过\"}","wo-review").andExpect(status().isOk());
        adminCommand("/admin/work-orders/"+wo+"/transition",admin,"{\"toStatus\":\"accepted\",\"version\":1,\"comment\":\"受理\"}","wo-accept").andExpect(status().isOk());
        adminCommand("/admin/work-orders/"+wo+"/transition",admin,"{\"toStatus\":\"processing\",\"version\":2}","wo-process").andExpect(status().isOk());
        adminCommand("/admin/work-orders/"+wo+"/transition",admin,"{\"toStatus\":\"completed\",\"version\":3}","wo-complete").andExpect(status().isOk()).andExpect(jsonPath("$.data.serviceStage").value("awaiting_rating"));
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
        String resource=mapper.readTree(adminCommand("/admin/resources",admin,"{\"type\":\"library_seat\",\"name\":\"三楼自习区\",\"capacity\":1,\"reservationMode\":\"capacity\"}","rs-create").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        String slot=mapper.readTree(adminCommand("/admin/resources/"+resource+"/slots",admin,"{\"slotDate\":\"2026-10-20\",\"startTime\":\"08:00:00\",\"endTime\":\"10:00:00\",\"capacity\":1}","rs-slot-1").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        String resv=mapper.readTree(command("/reservations",student,"{\"slotId\":\""+slot+"\",\"seatNo\":1}","rs-book-1").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE slot_id=?",Integer.class,Long.parseLong(slot)));
        command("/reservations",student2,"{\"slotId\":\""+slot+"\",\"seatNo\":1}","rs-book2").andExpect(status().isConflict());
        command("/reservations/"+resv+"/cancel",student,"{\"version\":0}","rs-cancel").andExpect(status().isOk());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE slot_id=?",Integer.class,Long.parseLong(slot)));
        command("/reservations",student2,"{\"slotId\":\""+slot+"\",\"seatNo\":1}","rs-book3").andExpect(status().isCreated());
    }

    @Test void publicResourcesHideInactiveDeletedAndForeignObjects() throws Exception {
        resourceFixture();
        jdbc.update("UPDATE resources SET status='inactive' WHERE id=10");
        mvc.perform(get("/api/v1/resources/10?campusCode=one")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/resources/10/slots?campusCode=one&date=2026-10-20")).andExpect(status().isNotFound());
        jdbc.update("UPDATE resources SET status='active',deleted_at=CURRENT_TIMESTAMP WHERE id=10");
        mvc.perform(get("/api/v1/resources/10?campusCode=one")).andExpect(status().isNotFound());
        jdbc.update("UPDATE resources SET deleted_at=NULL,campus_id=2 WHERE id=10");
        mvc.perform(get("/api/v1/resources/10?campusCode=one")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/resources/10/slots?campusCode=one&date=2026-10-20")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/resources/999?campusCode=one")).andExpect(status().isNotFound());
    }

    @Test void invisibleIssuesRejectInteractionsAndDeletedOwnerReads() throws Exception {
        issueFixture();
        command("/campus-issues/20/follows",student2,"{}","private-follow").andExpect(status().isNotFound());
        command("/campus-issues/20/evidences",student2,"{\"content\":\"private evidence\"}","private-evidence").andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/campus-issues/20/vote").header("Authorization",student2).header("Idempotency-Key","private-vote").contentType("application/json").content("{\"value\":-1}")).andExpect(status().isNotFound());
        jdbc.update("UPDATE campus_issues SET deleted_at=CURRENT_TIMESTAMP WHERE id=20");
        mvc.perform(get("/api/v1/campus-issues/20").header("Authorization",student)).andExpect(status().isNotFound());
    }

    @Test void publicIssueStatusMustAlsoBeApproved() throws Exception {
        issueFixture();jdbc.update("UPDATE campus_issues SET visibility='public' WHERE id=20");
        mvc.perform(get("/api/v1/campus-issues?campusCode=one")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/api/v1/campus-issues/20?campusCode=one")).andExpect(status().isNotFound());
    }

    @Test void invalidDateAndMapFiltersAreClientErrors() throws Exception {
        resourceFixture();
        mvc.perform(get("/api/v1/resources/10/slots?campusCode=one&date=2026-02-30")).andExpect(status().isBadRequest());
        for(String box:List.of("NaN,0,1,1","2,0,1,1","0,-91,1,1","oops,0,1,1"))
            mvc.perform(get("/api/v1/campus-issues/map").param("campusCode","one").param("bbox",box)).andExpect(status().isBadRequest());
        for(int limit:new int[]{0,-1,201})mvc.perform(get("/api/v1/campus-issues/map").param("campusCode","one").param("limit",Integer.toString(limit))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/reservations?dateFrom=2026-10-20&dateTo=2026-10-19").header("Authorization",student)).andExpect(status().isBadRequest());
    }

    @Test void cancelledReservationCannotBeApprovedAndManualSourceStaysPending() throws Exception {
        resourceFixture();
        String booking=mapper.readTree(command("/reservations",student,"{\"slotId\":\"11\",\"seatNo\":1}","manual-book").andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("pending")).andExpect(jsonPath("$.data.sourceType").value("manual")).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        assertNotNull(jdbc.queryForObject("SELECT pending_expires_at FROM reservations WHERE id=?",java.sql.Timestamp.class,Long.parseLong(booking)));
        command("/reservations/"+booking+"/cancel",student,"{\"version\":0}","manual-cancel").andExpect(status().isOk());
        adminCommand("/admin/reservations/"+booking+"/decision",admin,"{\"decision\":\"approve\",\"version\":1,\"reason\":\"reviewed\"}","manual-reapprove").andExpect(status().isConflict());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies",Integer.class));
    }

    @Test void slotDefaultsParentCapacityAndPreventsOverlappingSlots() throws Exception {
        resourceFixture();
        adminCommand("/admin/resources/10/slots",admin,"{\"slotDate\":\"2026-10-21\",\"startTime\":\"08:00\",\"endTime\":\"10:00\"}","capacity-default").andExpect(status().isCreated()).andExpect(jsonPath("$.data.capacity").value(1));
        adminCommand("/admin/resources/10/slots",admin,"{\"slotDate\":\"2026-10-20\",\"startTime\":\"08:30\",\"endTime\":\"09:00\",\"capacity\":1}","slot-overlap").andExpect(status().isConflict());
    }

    @Test void reservationDateAndResourceFiltersAreEffective() throws Exception {
        resourceFixture();
        jdbc.update("INSERT INTO reservations(id,campus_id,user_id,slot_id,seat_no,status,source_type,version,created_at,updated_at) VALUES(12,1,101,11,1,'cancelled','manual',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/v1/me/reservations?dateFrom=2026-10-21").header("Authorization",student)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/api/v1/admin/reservations?resourceId=99").header("Authorization",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test void workOrderApprovalPersistsAwaitingAcceptanceAndFlatDetail() throws Exception {
        String wo=mapper.readTree(command("/work-orders",student,"{\"category\":\"dorm\",\"description\":\"repair\",\"urgency\":\"normal\"}","approval-create").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        adminCommand("/admin/work-orders/"+wo+"/transition",admin,"{\"toStatus\":\"reviewing\",\"version\":0,\"comment\":\"review approved\"}","approval-review").andExpect(status().isOk()).andExpect(jsonPath("$.data.serviceStage").value("pending_acceptance"));
        mvc.perform(get("/api/v1/work-orders/"+wo).header("Authorization",student)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(wo)).andExpect(jsonPath("$.data.images").isArray()).andExpect(jsonPath("$.data.sourceType").value("manual"));
    }

    @Test void reservationReplayKeepsComposedDtoAndDoesNotOccupyTwice() throws Exception {
        resourceFixture();String body="{\"slotId\":\"11\",\"seatNo\":1}";
        var first=command("/reservations",student,body,"replay-reservation").andExpect(status().isCreated()).andReturn();
        var again=command("/reservations",student,body,"replay-reservation").andExpect(status().isCreated()).andReturn();
        assertEquals(mapper.readTree(first.getResponse().getContentAsString()).path("data"),mapper.readTree(again.getResponse().getContentAsString()).path("data"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies",Integer.class));
    }

    @Test void wrongTokenAndVersionDoNotConsumeCorrectTokenAndReplayIsRejected() throws Exception {
        demoBookingFixture();
        adminCommand("/admin/reservations/12/check-in-token",student,"{}","token-denied").andExpect(status().isUnauthorized());
        String token=mapper.readTree(adminCommand("/admin/reservations/12/check-in-token",admin,"{}","token-issue").andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("checkInToken").asString();
        command("/reservations/12/check-in",student,"{\"version\":0,\"checkInToken\":\"wrong-token\"}","token-wrong").andExpect(status().isConflict());
        command("/reservations/12/check-in",student,"{\"version\":1,\"checkInToken\":\""+token+"\"}","token-version").andExpect(status().isConflict());
        command("/reservations/12/check-in",student,"{\"version\":0,\"checkInToken\":\""+token+"\"}","token-correct").andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("12")).andExpect(jsonPath("$.data.resource.id").value("10"));
        command("/reservations/12/check-in",student,"{\"version\":1,\"checkInToken\":\""+token+"\"}","token-replay").andExpect(status().isConflict());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM reservation_checkins",Integer.class));
    }

    @Test void tokenWindowUsesShanghaiStartInclusiveEndExclusiveAndBoundsExpiry() throws Exception {
        demoBookingFixture();clock.value=Instant.parse("2026-10-19T23:59:59Z");
        adminCommand("/admin/reservations/12/check-in-token",admin,"{}","token-early").andExpect(status().isConflict());
        clock.value=Instant.parse("2026-10-20T01:58:00Z");
        String token=mapper.readTree(adminCommand("/admin/reservations/12/check-in-token",admin,"{}","token-late").andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresAt").value("2026-10-20T02:00:00Z")).andReturn().getResponse().getContentAsString()).path("data").path("checkInToken").asString();
        clock.value=Instant.parse("2026-10-20T02:00:00Z");
        command("/reservations/12/check-in",student,"{\"version\":0,\"checkInToken\":\""+token+"\"}","token-at-end").andExpect(status().isConflict());
        adminCommand("/admin/reservations/12/check-in-token",admin,"{}","token-after").andExpect(status().isConflict());
    }

    @Test void simultaneousLastSeatHasOneWinner() throws Exception {
        resourceFixture();var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->{ready.countDown();go.await();return command("/reservations",student,"{\"slotId\":\"11\",\"seatNo\":1}","race-first").andReturn().getResponse().getStatus();});
            var second=pool.submit(()->{ready.countDown();go.await();return command("/reservations",student2,"{\"slotId\":\"11\",\"seatNo\":1}","race-second").andReturn().getResponse().getStatus();});
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));go.countDown();var statuses=List.of(first.get(15,java.util.concurrent.TimeUnit.SECONDS),second.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,statuses.stream().filter(s->s==201).count());assertEquals(1,statuses.stream().filter(s->s==409).count());
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies",Integer.class));
    }

    @Test void occupiedSeatsPreventCapacityShrinkAndModeSwitch() throws Exception {
        resourceFixture();jdbc.update("UPDATE resources SET capacity=2 WHERE id=10");jdbc.update("UPDATE resource_slots SET capacity=2 WHERE id=11");
        command("/reservations",student,"{\"slotId\":\"11\",\"seatNo\":2}","occupied-seat2").andExpect(status().isCreated());
        adminCommand("/admin/resources/10/update",admin,"{\"capacity\":1,\"version\":0}","shrink-resource").andExpect(status().isConflict());
        adminCommand("/admin/resource-slots/11/update",admin,"{\"capacity\":1,\"version\":0,\"reason\":\"shrink\"}","shrink-slot").andExpect(status().isConflict());
        adminCommand("/admin/resources/10/update",admin,"{\"reservationMode\":\"exclusive\",\"version\":0}","switch-mode").andExpect(status().isConflict());
    }

    @Test void legacySeatZeroCannotBeHiddenByParentCapacityFallback()throws Exception {
        demoBookingFixture();jdbc.update("UPDATE resources SET capacity=5 WHERE id=10");jdbc.update("UPDATE resource_slots SET capacity=NULL WHERE id=11");jdbc.update("UPDATE reservations SET seat_no=0 WHERE id=12");jdbc.update("UPDATE resource_occupancies SET seat_no=0 WHERE id=13");
        command("/reservations",student2,"{\"slotId\":\"11\",\"seatNo\":1}","legacy-zero").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RESERVATION_CONFIGURATION_INVALID"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE seat_no=0",Integer.class));
        mvc.perform(get("/api/v1/resource-slots/11/availability").header("Authorization",student2)).andExpect(status().isOk()).andExpect(jsonPath("$.data.canReserve").value(false)).andExpect(jsonPath("$.data.availableSeatNos.length()").value(0)).andExpect(jsonPath("$.data.slot.remaining").value(0));
    }

    @Test void pendingHoldsExpireAndOfficialProviderFailsClosed() throws Exception {
        resourceFixture();String booking=mapper.readTree(command("/reservations",student,"{\"slotId\":\"11\",\"seatNo\":1}","pending-old").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        clock.value=clock.value.plusSeconds(901);
        command("/reservations",student2,"{\"slotId\":\"11\",\"seatNo\":1}","pending-new").andExpect(status().isCreated());
        assertEquals("cancelled",jdbc.queryForObject("SELECT status FROM reservations WHERE id=?",String.class,Long.parseLong(booking)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies",Integer.class));
        jdbc.update("UPDATE resources SET source_type='official' WHERE id=10");jdbc.update("DELETE FROM resource_occupancies");
        command("/reservations",student,"{\"slotId\":\"11\",\"seatNo\":1}","official-book").andExpect(status().isServiceUnavailable());
    }

    @Test void idlePendingHoldsAreReleasedByExpiryWorker() throws Exception {
        resourceFixture();command("/reservations",student,"{\"slotId\":\"11\",\"seatNo\":1}","idle-pending").andExpect(status().isCreated());
        clock.value=clock.value.plusSeconds(901);expiryWorker.run();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies",Integer.class));
        assertEquals("cancelled",jdbc.queryForObject("SELECT status FROM reservations",String.class));
    }

    @Test void issueModerationAndEvidenceStatusPersistAndVoteCountIsTotal() throws Exception {
        issueFixture();
        adminCommand("/admin/campus-issues/20/transition",admin,"{\"toStatus\":\"completed\",\"version\":0}","issue-skip").andExpect(status().isConflict());
        adminCommand("/admin/campus-issues/20/decision",admin,"{\"decision\":\"publish\",\"version\":0,\"reason\":\"human reviewed\"}","issue-review").andExpect(status().isOk());
        assertEquals("human reviewed",jdbc.queryForObject("SELECT comment FROM campus_issue_events WHERE issue_id=20",String.class));
        String evidence=mapper.readTree(command("/campus-issues/20/evidences",student2,"{\"content\":\"Evidence needs review\"}","issue-evidence").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        assertEquals("pending",jdbc.queryForObject("SELECT review_status FROM campus_issue_evidences WHERE id=?",String.class,Long.parseLong(evidence)));
        for(String bearer:List.of(student,student2))mvc.perform(put("/api/v1/campus-issues/20/vote").header("Authorization",bearer).header("Idempotency-Key","total-vote").contentType("application/json").content("{\"value\":-1}")).andExpect(status().isOk());
        assertEquals(2,jdbc.queryForObject("SELECT vote_count FROM campus_issues WHERE id=20",Integer.class));
    }

    @Test void sourceFieldsEquipmentRegionsAndTouristDefaultAreReturned() throws Exception {
        jdbc.update("INSERT INTO campus_regions(id,campus_id,name,status) VALUES(30,1,'Library','active')");
        adminCommand("/admin/resources",admin,"{\"type\":\"library_seat\",\"name\":\"Room\",\"capacity\":3,\"reservationMode\":\"capacity\",\"regionId\":\"30\",\"equipment\":{\"projector\":true}}","region-resource").andExpect(status().isCreated()).andExpect(jsonPath("$.data.equipment.projector").value(true)).andExpect(jsonPath("$.data.regionId").value("30"));
        mvc.perform(get("/api/v1/resources?regionId=30")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1)).andExpect(jsonPath("$.data.items[0].sourceType").value("manual")).andExpect(jsonPath("$.data.items[0].sourceFields").doesNotExist());
        mvc.perform(get("/api/v1/resources?regionId=31")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/api/v1/campus-issues/map?bbox=0,0,1,1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/campus-issues/map?bbox=0,0,0.01,0.01")).andExpect(status().isOk());
    }

    @Test void configuredSmallerMapAreaIsEnforced(){
        var bounded=new com.caizhitong.issue.IssueService(jdbc,null,null,clock,mapper,null,null,null,null,0.5);
        var error=assertThrows(com.caizhitong.common.BusinessException.class,()->bounded.map(1,"0,0,0.01,0.01",null,10));assertEquals(400,error.getStatus());
        assertEquals(0,bounded.map(1,"0,0,0.001,0.001",null,10).points().size());
    }

    @Test void actualFiltersAreBoundToSignedCursors()throws Exception {
        resourceFixture();jdbc.update("INSERT INTO reservations(id,campus_id,user_id,slot_id,seat_no,status,source_type,version,created_at,updated_at) VALUES(12,1,101,11,1,'cancelled','manual',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(13,1,101,11,1,'cancelled','manual',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        String cursor=mapper.readTree(mvc.perform(get("/api/v1/me/reservations?limit=1").header("Authorization",student)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("nextCursor").asString();
        mvc.perform(get("/api/v1/me/reservations").header("Authorization",student).param("limit","1").param("dateFrom","2026-10-20").param("cursor",cursor)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/resources?type=wrong")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/campus-issues?category=wrong")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/work-orders?status=wrong").header("Authorization",student)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/campus-issues?status=wrong").header("Authorization",student)).andExpect(status().isBadRequest());
    }

    @Test void workOrderAssignmentIsStaffOnlyAndAuditableAndRatingIsOwnerOnlyOnce()throws Exception {
        String id=mapper.readTree(command("/work-orders",student,"{\"category\":\"other\",\"description\":\"repair\",\"urgency\":\"normal\"}","assign-create").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        adminCommand("/admin/work-orders/"+id+"/assign",admin,"{\"assigneeId\":\"102\",\"version\":0,\"reason\":\"assign\"}","assign-student").andExpect(status().isNotFound());
        adminCommand("/admin/work-orders/"+id+"/assign",admin,"{\"assigneeId\":\"900\",\"version\":0,\"reason\":\"assigned to staff\"}","assign-staff").andExpect(status().isOk());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM work_order_events WHERE work_order_id=? AND comment='assigned to staff'",Integer.class,Long.parseLong(id)));
        jdbc.update("UPDATE work_orders SET status='completed' WHERE id=?",Long.parseLong(id));
        command("/work-orders/"+id+"/ratings",student2,"{\"score\":5}","rate-other").andExpect(status().isNotFound());
        command("/work-orders/"+id+"/ratings",student,"{\"score\":5}","rate-owner").andExpect(status().isCreated());
        command("/work-orders/"+id+"/ratings",student,"{\"score\":4}","rate-again").andExpect(status().isConflict());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM work_order_ratings WHERE work_order_id=?",Integer.class,Long.parseLong(id)));
    }

    @Test void sourceMetadataReusesExistingExternalMappingAndKeepsNullFields()throws Exception {
        resourceFixture();jdbc.update("INSERT INTO external_systems VALUES(31,1,'library','manual','active')");jdbc.update("INSERT INTO external_references VALUES(32,31,'resource',10,'synthetic-library-room')");
        var data=mapper.readTree(mvc.perform(get("/api/v1/resources/10")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals("manual",data.path("sourceType").asString());assertEquals("synthetic-library-room",data.path("externalId").asString());
        for(String key:List.of("sourceType","sourceName","updatedAt","isStale","officialReference","externalId"))assertTrue(data.has(key),key);
        assertTrue(data.path("officialReference").isNull());assertFalse(data.has("sourceFields"));
    }

    @Test void approvalRejectsMaintenanceSlotAndTerminalReservations()throws Exception {
        resourceFixture();jdbc.update("INSERT INTO reservations(id,campus_id,user_id,slot_id,seat_no,status,source_type,version,created_at,updated_at,pending_expires_at) VALUES(12,1,101,11,1,'pending','manual',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'2026-10-20 00:15:00')");jdbc.update("INSERT INTO resource_occupancies VALUES(13,11,12,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("UPDATE resource_slots SET status='maintenance' WHERE id=11");
        adminCommand("/admin/reservations/12/decision",admin,"{\"decision\":\"approve\",\"version\":0,\"reason\":\"reviewed\"}","maintenance-approval").andExpect(status().isConflict());
        for(String terminal:List.of("completed","checked_in","cancelled","rejected")){
            jdbc.update("UPDATE reservations SET status=? WHERE id=12",terminal);
            adminCommand("/admin/reservations/12/decision",admin,"{\"decision\":\"approve\",\"version\":0,\"reason\":\"reviewed\"}","terminal-"+terminal).andExpect(status().isConflict());
        }
    }

    @Test void expiryCrossedBetweenCleanupStatementsNeverLeavesCancelledOccupancy()throws Exception {
        resourceFixture();jdbc.update("UPDATE resources SET capacity=2 WHERE id=10");jdbc.update("UPDATE resource_slots SET capacity=2 WHERE id=11");
        jdbc.update("INSERT INTO reservations(id,campus_id,user_id,slot_id,seat_no,status,source_type,version,created_at,updated_at,pending_expires_at) VALUES(12,1,101,11,1,'pending','manual',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)",java.time.LocalDateTime.ofInstant(clock.value.plusMillis(1),ZoneOffset.UTC));
        jdbc.update("INSERT INTO resource_occupancies VALUES(13,11,12,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        org.mockito.Mockito.doAnswer(call->{Object result=call.callRealMethod();clock.value=clock.value.plusMillis(2);return result;}).when(jdbc).update(org.mockito.ArgumentMatchers.startsWith("DELETE FROM resource_occupancies WHERE reservation_id IN"),org.mockito.ArgumentMatchers.any(Object[].class));
        command("/reservations",student2,"{\"slotId\":\"11\",\"seatNo\":2}","cross-expiry-submit").andExpect(status().isCreated());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies o JOIN reservations v ON v.id=o.reservation_id WHERE v.status='cancelled'",Integer.class));
        expiryWorker.run();
        assertEquals("cancelled",jdbc.queryForObject("SELECT status FROM reservations WHERE id=12",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE reservation_id=12",Integer.class));
    }

    @Test void sameSeatExpiringAfterCleanupReturnsConflictThenWorkerAllowsRetry()throws Exception {
        resourceFixture();
        jdbc.update("INSERT INTO reservations(id,campus_id,user_id,slot_id,seat_no,status,source_type,version,created_at,updated_at,pending_expires_at) VALUES(12,1,101,11,1,'pending','manual',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)",LocalDateTime.ofInstant(clock.value.plusMillis(1),ZoneOffset.UTC));
        jdbc.update("INSERT INTO resource_occupancies VALUES(13,11,12,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        org.mockito.Mockito.doAnswer(call->{Object result=call.callRealMethod();clock.value=clock.value.plusMillis(2);return result;}).when(jdbc).update(org.mockito.ArgumentMatchers.startsWith("DELETE FROM resource_occupancies WHERE reservation_id IN"),org.mockito.ArgumentMatchers.any(Object[].class));
        String body="{\"slotId\":\"11\",\"seatNo\":1}";
        command("/reservations",student2,body,"same-seat-expiry-retry").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RESERVATION_CONFLICT"));
        assertEquals("pending",jdbc.queryForObject("SELECT status FROM reservations WHERE id=12",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM reservations",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE reservation_id=12",Integer.class));
        expiryWorker.run();
        assertEquals("cancelled",jdbc.queryForObject("SELECT status FROM reservations WHERE id=12",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE reservation_id=12",Integer.class));
        command("/reservations",student2,body,"same-seat-expiry-retry").andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("pending"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM reservations",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE slot_id=11 AND seat_no=1",Integer.class));
    }

    @Test void overlappingSeatExpiringAfterCleanupStaysBlockedUntilPhysicalRelease()throws Exception {
        resourceFixture();
        jdbc.update("INSERT INTO resource_slots(id,resource_id,slot_date,start_time,end_time,capacity,status,version,created_at,updated_at) VALUES(14,10,'2026-10-20','09:00','11:00',1,'available',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO reservations(id,campus_id,user_id,slot_id,seat_no,status,source_type,version,created_at,updated_at,pending_expires_at) VALUES(12,1,101,11,1,'pending','manual',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)",LocalDateTime.ofInstant(clock.value.plusMillis(1),ZoneOffset.UTC));
        jdbc.update("INSERT INTO resource_occupancies VALUES(13,11,12,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        org.mockito.Mockito.doAnswer(call->{Object result=call.callRealMethod();clock.value=clock.value.plusMillis(2);return result;}).when(jdbc).update(org.mockito.ArgumentMatchers.startsWith("DELETE FROM resource_occupancies WHERE reservation_id IN"),org.mockito.ArgumentMatchers.any(Object[].class));
        String body="{\"slotId\":\"14\",\"seatNo\":1}";
        command("/reservations",student2,body,"overlap-expiry-retry").andExpect(status().isConflict());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM reservations",Integer.class));
        expiryWorker.run();
        command("/reservations",student2,body,"overlap-expiry-retry").andExpect(status().isCreated());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE slot_id=14 AND seat_no=1",Integer.class));
    }

    @Test void overlappingCapacitySlotsReportDistinctBlockedSeatsAndMatchReservationWrites()throws Exception {
        resourceFixture();jdbc.update("UPDATE resources SET capacity=2 WHERE id=10");jdbc.update("UPDATE resource_slots SET capacity=2 WHERE id=11");
        jdbc.update("INSERT INTO resource_slots(id,resource_id,slot_date,start_time,end_time,capacity,status,version,created_at,updated_at) VALUES(14,10,'2026-10-20','09:00','11:00',2,'available',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(15,10,'2026-10-20','09:15','10:30',2,'available',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO reservations(id,campus_id,user_id,slot_id,seat_no,status,source_type,version,created_at,updated_at) VALUES(12,1,101,11,1,'confirmed','manual',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(16,1,101,15,1,'confirmed','manual',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO resource_occupancies VALUES(13,11,12,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(17,15,16,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/v1/resource-slots/14/availability").header("Authorization",student2)).andExpect(status().isOk()).andExpect(jsonPath("$.data.availableSeatNos.length()").value(1)).andExpect(jsonPath("$.data.availableSeatNos[0]").value(2)).andExpect(jsonPath("$.data.slot.remaining").value(1));
        mvc.perform(get("/api/v1/resources/10/slots?date=2026-10-20")).andExpect(status().isOk()).andExpect(jsonPath("$.data[1].remaining").value(1));
        command("/reservations",student2,"{\"slotId\":\"14\",\"seatNo\":1}","overlap-capacity-block").andExpect(status().isConflict());
        command("/reservations",student2,"{\"slotId\":\"14\",\"seatNo\":2}","overlap-capacity-free").andExpect(status().isCreated()).andExpect(jsonPath("$.data.slot.remaining").value(0));
    }

    @Test void overlappingExclusiveSlotsStayUnavailableUntilOriginalBookingIsCancelled()throws Exception {
        resourceFixture();jdbc.update("UPDATE resources SET capacity=NULL,reservation_mode='exclusive' WHERE id=10");jdbc.update("UPDATE resource_slots SET capacity=NULL WHERE id=11");
        jdbc.update("INSERT INTO resource_slots(id,resource_id,slot_date,start_time,end_time,status,version,created_at,updated_at) VALUES(14,10,'2026-10-20','09:00','11:00','available',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO reservations(id,campus_id,user_id,slot_id,seat_no,status,source_type,version,created_at,updated_at) VALUES(12,1,101,11,0,'confirmed','manual',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO resource_occupancies VALUES(13,11,12,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/v1/resource-slots/14/availability").header("Authorization",student2)).andExpect(status().isOk()).andExpect(jsonPath("$.data.canReserve").value(false)).andExpect(jsonPath("$.data.availableSeatNos.length()").value(0)).andExpect(jsonPath("$.data.slot.remaining").value(0));
        command("/reservations",student2,"{\"slotId\":\"14\",\"seatNo\":0}","overlap-exclusive-block").andExpect(status().isConflict());
        command("/reservations/12/cancel",student,"{\"version\":0}","overlap-exclusive-cancel").andExpect(status().isOk());
        mvc.perform(get("/api/v1/resource-slots/14/availability").header("Authorization",student2)).andExpect(status().isOk()).andExpect(jsonPath("$.data.availableSeatNos.length()").value(1)).andExpect(jsonPath("$.data.availableSeatNos[0]").value(0)).andExpect(jsonPath("$.data.slot.remaining").value(1));
        command("/reservations",student2,"{\"slotId\":\"14\",\"seatNo\":0}","overlap-exclusive-retry").andExpect(status().isCreated());
    }

    @Test void legacyReviewingOrderCanReceiveAuditedApprovalBeforeAcceptance()throws Exception {
        String id=mapper.readTree(command("/work-orders",student,"{\"category\":\"other\",\"description\":\"legacy repair\",\"urgency\":\"normal\"}","legacy-review-create").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        jdbc.update("UPDATE work_orders SET status='reviewing',review_approved_at=NULL WHERE id=?",Long.parseLong(id));
        adminCommand("/admin/work-orders/"+id+"/transition",admin,"{\"toStatus\":\"accepted\",\"version\":0}","legacy-no-approval").andExpect(status().isConflict());
        adminCommand("/admin/work-orders/"+id+"/transition",admin,"{\"toStatus\":\"reviewing\",\"version\":0,\"comment\":\"Human supplemental approval\"}","legacy-supplemental-review").andExpect(status().isOk()).andExpect(jsonPath("$.data.serviceStage").value("pending_acceptance"));
        assertNotNull(jdbc.queryForObject("SELECT review_approved_at FROM work_orders WHERE id=?",java.sql.Timestamp.class,Long.parseLong(id)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM work_order_events WHERE work_order_id=? AND from_status='reviewing' AND to_status='reviewing' AND operator_id=900 AND comment='Human supplemental approval'",Integer.class,Long.parseLong(id)));
        adminCommand("/admin/work-orders/"+id+"/transition",admin,"{\"toStatus\":\"reviewing\",\"version\":1,\"comment\":\"Duplicate approval\"}","legacy-repeat-review").andExpect(status().isConflict());
        adminCommand("/admin/work-orders/"+id+"/transition",admin,"{\"toStatus\":\"accepted\",\"version\":1}","legacy-accept-reviewed").andExpect(status().isOk());
    }

    private void demoBookingFixture(){resourceFixture();jdbc.update("UPDATE resources SET source_type='demo' WHERE id=10");jdbc.update("INSERT INTO reservations(id,campus_id,user_id,slot_id,seat_no,status,source_type,version,created_at,updated_at) VALUES(12,1,101,11,1,'confirmed','demo',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");jdbc.update("INSERT INTO resource_occupancies VALUES(13,11,12,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");}

    private void resourceFixture(){
        jdbc.update("INSERT INTO resources(id,campus_id,type,name,capacity,status,source_type,version,created_at,updated_at) VALUES(10,1,'library_seat','Reading',1,'active','manual',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO resource_slots(id,resource_id,slot_date,start_time,end_time,capacity,status,version,created_at,updated_at) VALUES(11,10,'2026-10-20','08:00:00','10:00:00',1,'available',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }
    private void issueFixture(){jdbc.update("INSERT INTO campus_issues(id,campus_id,user_id,title,category,status,source_type,version,created_at,updated_at,visibility) VALUES(20,1,101,'Private issue','other','pending','manual',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'private')");}

    private ResultActions command(String path,String bearer,String body,String key) throws Exception {
        return mvc.perform(post("/api/v1"+path).header("Authorization",bearer).header("Idempotency-Key",key).contentType("application/json").content(body));
    }
    private ResultActions adminCommand(String path,String bearer,String body,String key) throws Exception {
        var bootstrap=mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        String csrf=mapper.readTree(bootstrap.getResponse().getContentAsString()).path("data").path("token").asString();
        return mvc.perform(post("/api/v1"+path).secure(true).cookie(bootstrap.getResponse().getCookie("ADMIN-XSRF-TOKEN")).header("X-ADMIN-CSRF",csrf).header("Authorization",bearer).header("Idempotency-Key",key).contentType("application/json").content(body));
    }
    @TestConfiguration static class Fixtures {
        @Bean @Primary TestClock fixtureClock(){return new TestClock();}
        @Bean @Primary com.caizhitong.common.SnowflakeIds fixtureIds(){return new com.caizhitong.common.SnowflakeIds(Clock.systemUTC(),0);}
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
    static class TestClock extends Clock {
        volatile Instant value=Instant.parse("2026-10-20T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}
    }
}
