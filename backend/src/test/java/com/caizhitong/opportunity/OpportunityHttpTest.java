package com.caizhitong.opportunity;

import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.integration.ai.DeepSeekClient;
import com.caizhitong.integration.storage.ObjectStorage;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * A 模块(Task 5+6)端到端隔离测试:机会/通知/AI 解析/资格/收藏/提醒/自行提交/清单/预检/jobs 收据。
 * H2 隔离库,外部 AI/存储为测试替身;真实 DeepSeek/MySQL 联调另行验收,不把替身当真实联调。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:opportunity_http;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OpportunityHttpTest.Fixtures.class)
@Sql(scripts = { "/identity-schema.sql", "/files-schema.sql", "/opportunity-schema.sql" }, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class OpportunityHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokenService sessions;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired TestAi ai;
    @Autowired TestStorage storage;
    @Autowired SensitiveDataCrypto crypto;

    static final Instant NOW = Instant.parse("2026-10-15T06:00:00Z");

    @BeforeEach
    void seed() {
        for (String table : List.of("precheck_results", "precheck_tasks", "material_checklist_items", "material_checklists",
                "opportunity_rule_snapshots", "user_opportunity_submissions", "reminders", "favorites", "ai_parse_fields",
                "ai_parse_tasks", "opportunity_eligibility_checks", "opportunity_eligibility_rules", "opportunity_material_items",
                "opportunities", "notices", "material_usage_records", "material_versions", "material_files", "file_attachments",
                "async_jobs", "api_idempotency_records", "admin_audit_logs", "authorizations", "user_profiles",
                "student_verifications", "user_phones", "user_roles", "roles", "users", "campuses"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO campuses VALUES(1,'大学','one','active',NULL)");
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(101,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(102,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(900,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO roles(id,code,name) VALUES(7,'campus_admin','校园管理员')");
        jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id) VALUES(1,900,7,1)");
        storage.objects.clear();
        ai.result = null;
        ai.calls = 0;
    }

    void phone(long user) {
        jdbc.update("INSERT INTO user_phones(id,user_id,phone_encrypted,phone_search,verified,created_at) VALUES(?,?,?,?,1,CURRENT_TIMESTAMP)",
                user, user, crypto.encrypt("phone", "+861380000" + user), java.util.HexFormat.of().formatHex(crypto.searchHash("phone", "+861380000" + user)));
        jdbc.update("INSERT INTO authorizations(id,user_id,type,status,granted_at,created_at) VALUES(?,?,'phone','granted',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", user, user);
    }

    void verify(long user) {
        jdbc.update("INSERT INTO student_verifications(id,user_id,campus_id,student_id_encrypted,student_id_search,status,created_at,updated_at) VALUES(?,?,1,?,?,'verified',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                user, user, crypto.encrypt("student", "S" + user), java.util.HexFormat.of().formatHex(crypto.searchHash("student", "S" + user)));
    }

    void student(long user) {
        phone(user);
        verify(user);
    }

    String bearer(long user, SessionKind kind) { return "Bearer " + sessions.issue(user, 1, kind).accessToken(); }

    ResultActions command(String path, String bearer, String body, String key) throws Exception {
        return mvc.perform(post("/api/v1" + path).header("Authorization", bearer).header("Idempotency-Key", key).contentType("application/json").content(body));
    }

    ResultActions adminCommand(String path, String bearer, String body, String key) throws Exception {
        var bootstrap = mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        String csrf = new tools.jackson.databind.json.JsonMapper().readTree(bootstrap.getResponse().getContentAsString()).path("data").path("token").asString();
        return mvc.perform(post("/api/v1" + path).secure(true).cookie(bootstrap.getResponse().getCookie("ADMIN-XSRF-TOKEN"))
                .header("X-ADMIN-CSRF", csrf).header("Authorization", bearer).header("Idempotency-Key", key)
                .contentType("application/json").content(body));
    }

    long publishedOpportunity(long id, String status, String deadline) {
        jdbc.update("INSERT INTO opportunities(id,campus_id,title,category,organizer,summary,deadline,status,source_type,rules_version,version,created_at,updated_at) VALUES(?,1,'奖学金','scholarship','学院',?,?,?,'manual',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, "summary-" + id, deadline == null ? null : LocalDateTime.parse(deadline), status);
        return id;
    }

    // ---------- 公开查询 ----------

    @Test
    void publicListAndDetailExposeOnlyPublishedExpiredAndRejectDraftCrossCampus() throws Exception {
        publishedOpportunity(11, "published", null);
        publishedOpportunity(12, "expired", "2026-10-01T00:00:00");
        publishedOpportunity(13, "draft", null);
        jdbc.update("INSERT INTO opportunities(id,campus_id,title,category,status,source_type,rules_version,version,created_at,updated_at) VALUES(14,2,'外校','scholarship','published','manual',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/v1/opportunities?campusCode=one&limit=10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value("12"))
                .andExpect(jsonPath("$.data.items[1].id").value("11"));
        mvc.perform(get("/api/v1/opportunities/11?campusCode=one")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.opportunity.id").value("11"))
                .andExpect(jsonPath("$.data.opportunity.sourceType").value("manual"))
                .andExpect(jsonPath("$.data.teamCount").value(0))
                .andExpect(jsonPath("$.data.isFavorited").value(false));
        mvc.perform(get("/api/v1/opportunities/13?campusCode=one")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/opportunities/14?campusCode=one")).andExpect(status().isNotFound());
    }

    @Test
    void listFiltersAndCursorBindScopeAndRejectBadInput() throws Exception {
        publishedOpportunity(21, "published", "2026-11-01T00:00:00");
        publishedOpportunity(22, "published", "2026-11-02T00:00:00");
        publishedOpportunity(23, "published", null);
        mvc.perform(get("/api/v1/opportunities?campusCode=one&category=competition")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/api/v1/opportunities?campusCode=one&category=scholarship&limit=2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value("21"))
                .andExpect(jsonPath("$.data.items[1].id").value("22"));
        String cursor = new tools.jackson.databind.json.JsonMapper()
                .readTree(mvc.perform(get("/api/v1/opportunities?campusCode=one&limit=2")).andReturn().getResponse().getContentAsString())
                .path("data").path("nextCursor").asString();
        mvc.perform(get("/api/v1/opportunities?campusCode=one&limit=2&cursor=" + cursor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("23"));
        mvc.perform(get("/api/v1/opportunities?campusCode=one&limit=2&category=competition&cursor=" + cursor)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/opportunities?campusCode=one&deadlineFrom=2026-12-01T00:00:00Z&deadlineTo=2026-11-01T00:00:00Z")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/opportunities?campusCode=one&category=not-a-category")).andExpect(status().isBadRequest());
    }

    @Test
    void expiryWorkerMovesOnlyPublishedPastDeadline() throws Exception {
        publishedOpportunity(31, "published", "2026-10-01T00:00:00");
        publishedOpportunity(32, "published", "2026-12-01T00:00:00");
        publishedOpportunity(33, "draft", "2026-10-01T00:00:00");
        context.getBean("opportunityExpiryWorker", Runnable.class).run();
        Assertions.assertEquals("expired", jdbc.queryForObject("SELECT status FROM opportunities WHERE id=31", String.class));
        Assertions.assertEquals("published", jdbc.queryForObject("SELECT status FROM opportunities WHERE id=32", String.class));
        Assertions.assertEquals("draft", jdbc.queryForObject("SELECT status FROM opportunities WHERE id=33", String.class));
    }

    // ---------- 资格检查 ----------

    @Test
    void eligibilityCheckRunsAsyncFromSnapshotAndNeverClaimsUnknownAsEligible() throws Exception {
        student(101);
        publishedOpportunity(41, "published", null);
        jdbc.update("INSERT INTO user_profiles(id,user_id,college,grade,skills,interests,version,created_at,updated_at) VALUES(1,101,'信息学院','2024','[\"Java\"]','[]',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO opportunity_eligibility_rules(id,opportunity_id,field_key,operator,value_json,severity,sort_order,created_at) VALUES(1,41,'college','eq','\"信息学院\"','hard',0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO opportunity_eligibility_rules(id,opportunity_id,field_key,operator,value_json,severity,sort_order,created_at) VALUES(2,41,'skills','contains','[\"Java\"]','hard',1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO opportunity_rule_snapshots(id,opportunity_id,rules_version,rules_json,material_requirements_json,created_at) VALUES(1,41,0,'[{\"field\":\"college\",\"operator\":\"eq\",\"value\":\"信息学院\",\"severity\":\"hard\",\"sortOrder\":0},{\"field\":\"skills\",\"operator\":\"contains\",\"value\":[\"Java\"],\"severity\":\"hard\",\"sortOrder\":1}]','[]',CURRENT_TIMESTAMP)");
        var body = new tools.jackson.databind.json.JsonMapper().readTree(
                command("/opportunities/41/eligibility-checks", bearer(101, SessionKind.STUDENT), "{}", "eligibility-once")
                        .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("pending")).andReturn().getResponse().getContentAsString());
        Assertions.assertEquals("eligibility_check", body.path("data").path("jobType").asString());
        context.getBean("eligibilityWorker", Runnable.class).run();
        Assertions.assertEquals("succeeded", jdbc.queryForObject("SELECT status FROM async_jobs WHERE job_type='eligibility_check'", String.class));
        Assertions.assertEquals("eligible", jdbc.queryForObject("SELECT conclusion FROM opportunity_eligibility_checks", String.class));
        mvc.perform(get("/api/v1/opportunities/41/eligibility-checks/latest").header("Authorization", bearer(101, SessionKind.STUDENT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.conclusion").value("eligible"))
                .andExpect(jsonPath("$.data.profileVersion").value(1))
                .andExpect(jsonPath("$.data.profileSnapshot").doesNotExist());
        mvc.perform(get("/api/v1/jobs/" + body.path("data").path("id").asString()).header("Authorization", bearer(101, SessionKind.STUDENT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result.conclusion").value("eligible"));
        mvc.perform(get("/api/v1/jobs/" + body.path("data").path("id").asString()).header("Authorization", bearer(102, SessionKind.STUDENT)))
                .andExpect(status().isNotFound());
        // 规则快照不随画像/规则修改而变:改画像后旧任务结果不变
        jdbc.update("UPDATE user_profiles SET college='外语学院',version=2 WHERE user_id=101");
        jdbc.update("INSERT INTO opportunity_eligibility_checks(id,opportunity_id,user_id,conclusion,reason_json,rules_version,profile_snapshot,profile_version,created_at) VALUES(99,41,101,'eligible','[]',0,'{}',1,CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/v1/opportunities/41/eligibility-checks/latest").header("Authorization", bearer(101, SessionKind.STUDENT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.conclusion").value("eligible"));
    }

    @Test
    void hardConflictIsNotEligibleAndUnknownIsPossible() throws Exception {
        student(101);
        publishedOpportunity(42, "published", null);
        jdbc.update("INSERT INTO user_profiles(id,user_id,college,grade,skills,interests,version,created_at,updated_at) VALUES(1,101,'外语学院','2024','[]','[]',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO opportunity_rule_snapshots(id,opportunity_id,rules_version,rules_json,material_requirements_json,created_at) VALUES(1,42,0,'[{\"field\":\"college\",\"operator\":\"eq\",\"value\":\"信息学院\",\"severity\":\"hard\",\"sortOrder\":0}]','[]',CURRENT_TIMESTAMP)");
        command("/opportunities/42/eligibility-checks", bearer(101, SessionKind.STUDENT), "{}", "elig-42a").andExpect(status().isAccepted());
        context.getBean("eligibilityWorker", Runnable.class).run();
        Assertions.assertEquals("not_eligible", jdbc.queryForObject("SELECT conclusion FROM opportunity_eligibility_checks", String.class));
        jdbc.update("DELETE FROM opportunity_eligibility_checks");
        jdbc.update("UPDATE opportunity_rule_snapshots SET rules_json='[{\"field\":\"grade\",\"operator\":\"range\",\"value\":{\"min\":\"2024\",\"max\":\"2026\"},\"severity\":\"hard\",\"sortOrder\":0}]' WHERE id=1");
        jdbc.update("UPDATE user_profiles SET grade='2025'");
        command("/opportunities/42/eligibility-checks", bearer(101, SessionKind.STUDENT), "{}", "elig-42b").andExpect(status().isAccepted());
        context.getBean("eligibilityWorker", Runnable.class).run();
        Assertions.assertEquals("eligible", jdbc.queryForObject("SELECT conclusion FROM opportunity_eligibility_checks", String.class));
        jdbc.update("DELETE FROM opportunity_eligibility_checks");
        jdbc.update("UPDATE opportunity_rule_snapshots SET rules_json='[{\"field\":\"未知字段\",\"operator\":\"eq\",\"value\":\"x\",\"severity\":\"hard\",\"sortOrder\":0}]' WHERE id=1");
        command("/opportunities/42/eligibility-checks", bearer(101, SessionKind.STUDENT), "{}", "elig-42c").andExpect(status().isAccepted());
        context.getBean("eligibilityWorker", Runnable.class).run();
        Assertions.assertEquals("possible", jdbc.queryForObject("SELECT conclusion FROM opportunity_eligibility_checks", String.class));
    }

    @Test
    void eligibilityRequiresVerifiedStudentAndVisibleOpportunity() throws Exception {
        phone(101);
        publishedOpportunity(43, "published", null);
        mvc.perform(post("/api/v1/opportunities/43/eligibility-checks").header("Authorization", bearer(101, SessionKind.STUDENT))
                .header("Idempotency-Key", "elig-no-student").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verify(101);
        mvc.perform(post("/api/v1/opportunities/999/eligibility-checks").header("Authorization", bearer(101, SessionKind.STUDENT))
                .header("Idempotency-Key", "elig-missing").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        student(102);
        mvc.perform(get("/api/v1/opportunities/43/eligibility-checks/latest").header("Authorization", bearer(102, SessionKind.STUDENT)))
                .andExpect(status().isNotFound());
    }

    // ---------- 收藏/提醒/自行提交 ----------

    @Test
    void favoritesAreIdempotentScopedAndHideUnavailableTargets() throws Exception {
        student(101);
        publishedOpportunity(51, "published", null);
        String bearer = bearer(101, SessionKind.STUDENT);
        mvc.perform(put("/api/v1/favorites/opportunity/51").header("Authorization", bearer).header("Idempotency-Key", "fav-once"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.favorited").value(true));
        mvc.perform(put("/api/v1/favorites/opportunity/51").header("Authorization", bearer).header("Idempotency-Key", "fav-once"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.favorited").value(true));
        Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM favorites", Integer.class));
        mvc.perform(get("/api/v1/me/favorites").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].available").value(true))
                .andExpect(jsonPath("$.data.items[0].target.id").value("51"));
        jdbc.update("UPDATE opportunities SET status='archived' WHERE id=51");
        mvc.perform(get("/api/v1/me/favorites").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].available").value(false))
                .andExpect(jsonPath("$.data.items[0].target").doesNotExist());
        mvc.perform(put("/api/v1/favorites/resource/1").header("Authorization", bearer).header("Idempotency-Key", "fav-resource"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/favorites/opportunity/51").header("Authorization", bearer).header("Idempotency-Key", "fav-delete"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.favorited").value(false));
        Assertions.assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM favorites", Integer.class));
    }

    @Test
    void remindersRejectPastTimeAndOnlyCancelPending() throws Exception {
        student(101);
        publishedOpportunity(61, "published", null);
        String bearer = bearer(101, SessionKind.STUDENT);
        command("/reminders", bearer, "{\"targetType\":\"opportunity\",\"targetId\":\"61\",\"remindAt\":\"2026-10-14T00:00:00Z\"}", "rem-past")
                .andExpect(status().isBadRequest());
        command("/reminders", bearer, "{\"targetType\":\"borrow\",\"targetId\":\"1\",\"remindAt\":\"2026-10-16T00:00:00Z\"}", "rem-borrow")
                .andExpect(status().isBadRequest());
        var created = new tools.jackson.databind.json.JsonMapper().readTree(
                command("/reminders", bearer, "{\"targetType\":\"opportunity\",\"targetId\":\"61\",\"remindAt\":\"2026-10-16T00:00:00Z\"}", "rem-once")
                        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("pending")).andReturn().getResponse().getContentAsString());
        String id = created.path("data").path("id").asString();
        mvc.perform(get("/api/v1/me/reminders").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(delete("/api/v1/reminders/" + id).header("Authorization", bearer).header("Idempotency-Key", "rem-cancel"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.cancelled").value(true));
        mvc.perform(delete("/api/v1/reminders/" + id).header("Authorization", bearer).header("Idempotency-Key", "rem-cancel-2"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/reminders/" + id).header("Authorization", bearer(102, SessionKind.STUDENT)).header("Idempotency-Key", "rem-other"))
                .andExpect(status().isNotFound());
    }

    @Test
    void submissionIsSingleCurrentRecordPerUserAndOpportunity() throws Exception {
        student(101);
        publishedOpportunity(71, "published", null);
        String bearer = bearer(101, SessionKind.STUDENT);
        mvc.perform(put("/api/v1/opportunities/71/submission").header("Authorization", bearer).header("Idempotency-Key", "sub-once")
                .contentType("application/json").content("{\"submittedAt\":\"2026-10-15T03:00:00Z\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("submitted"));
        mvc.perform(put("/api/v1/opportunities/71/submission").header("Authorization", bearer).header("Idempotency-Key", "sub-again")
                .contentType("application/json").content("{\"submittedAt\":\"2026-10-15T04:00:00Z\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.submittedAt").value("2026-10-15T04:00:00Z"));
        Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_opportunity_submissions", Integer.class));
        mvc.perform(put("/api/v1/opportunities/71/submission").header("Authorization", bearer).header("Idempotency-Key", "sub-baddate")
                .contentType("application/json").content("{\"submittedAt\":\"2026-10-15T03:00:00\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/opportunities/71/submission").header("Authorization", bearer(102, SessionKind.STUDENT)).header("Idempotency-Key", "sub-u102")
                .contentType("application/json").content("{\"submittedAt\":\"2026-10-15T03:00:00Z\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------- 管理端:通知与 AI 解析 ----------

    @Test
    void adminNoticeParseReviewPublishesOnlyDraftAndGatesOnConfirmedBasis() throws Exception {
        String admin = bearer(900, SessionKind.ADMIN);
        // 缺原文来源
        adminCommand("/admin/notices", admin, "{\"title\":\"x\"}", "notice-no-source").andExpect(status().isBadRequest());
        // SSRF:非 HTTPS
        adminCommand("/admin/notices", admin, "{\"title\":\"竞赛\",\"rawUrl\":\"http://10.0.0.1/x\"}", "notice-http").andExpect(status().isBadRequest());
        var created = new tools.jackson.databind.json.JsonMapper().readTree(
                adminCommand("/admin/notices", admin, "{\"title\":\"竞赛通知\",\"publisher\":\"教务处\",\"content\":\"竞赛通知:面向信息学院2024级,要求Java技能,截止2026-11-01,一等奖若干,联系邮箱a@b.c\"}", "notice-once")
                        .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("pending")).andReturn().getResponse().getContentAsString());
        String noticeId = created.path("data").path("id").asString();
        mvc.perform(get("/api/v1/admin/notices").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        // 版本冲突
        adminCommand("/admin/notices/" + noticeId + "/parse", admin, "{\"version\":9}", "parse-v9").andExpect(status().isConflict());
        var parse = new tools.jackson.databind.json.JsonMapper().readTree(
                adminCommand("/admin/notices/" + noticeId + "/parse", admin, "{\"version\":0}", "parse-once")
                        .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("pending")).andReturn().getResponse().getContentAsString());
        String taskId = parse.path("data").path("id").asString();
        // 重复解析同一代拒绝
        adminCommand("/admin/notices/" + noticeId + "/parse", admin, "{\"version\":1}", "parse-again").andExpect(status().isConflict());
        // worker:fake AI 返回固定 Schema 字段
        ai.result = new DeepSeekClient.Completion("""
                {"title":{"value":"竞赛通知","evidenceLocation":"竞赛通知","confidence":0.9},
                 "category":{"value":"competition","evidenceLocation":"竞赛","confidence":0.8},
                 "targetAudience":{"value":"信息学院2024级","evidenceLocation":"面向信息学院2024级","confidence":0.8},
                 "deadline":{"value":"2026-11-01T23:59:00+08:00","evidenceLocation":"截止2026-11-01","confidence":0.7}}
                """, "stop", "deepseek-flash", 1L);
        context.getBean("noticeParseWorker", Runnable.class).run();
        Assertions.assertEquals("succeeded", jdbc.queryForObject("SELECT status FROM ai_parse_tasks WHERE id=?", String.class, Long.parseLong(taskId)));
        Assertions.assertEquals("reviewing", jdbc.queryForObject("SELECT status FROM notices WHERE id=?", String.class, Long.parseLong(noticeId)));
        mvc.perform(get("/api/v1/admin/notice-parse-tasks/" + taskId).header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields.length()").value(4))
                .andExpect(jsonPath("$.data.fields[0].humanConfirmed").value(false));
        // review:非法字段键
        adminCommand("/admin/notice-parse-tasks/" + taskId + "/review", admin,
                "{\"fields\":[{\"key\":\"hack\",\"value\":\"x\"}],\"decision\":\"approve\",\"reason\":\"ok\"}", "review-bad-key").andExpect(status().isBadRequest());
        // approve 只生成 draft
        var reviewed = new tools.jackson.databind.json.JsonMapper().readTree(
                adminCommand("/admin/notice-parse-tasks/" + taskId + "/review", admin,
                        "{\"fields\":[{\"key\":\"title\",\"value\":\"竞赛通知\"},{\"key\":\"category\",\"value\":\"competition\"},{\"key\":\"targetAudience\",\"value\":\"信息学院2024级\"},{\"key\":\"deadline\",\"value\":\"2026-11-01T23:59:00+08:00\"}],\"decision\":\"approve\",\"reason\":\"逐字段已确认\"}",
                        "review-approve").andExpect(status().isOk()).andExpect(jsonPath("$.data.opportunityDraftId").isString()).andReturn().getResponse().getContentAsString());
        String draftId = reviewed.path("data").path("opportunityDraftId").asString();
        Assertions.assertEquals("draft", jdbc.queryForObject("SELECT status FROM opportunities WHERE id=?", String.class, Long.parseLong(draftId)));
        // 重复复核拒绝
        adminCommand("/admin/notice-parse-tasks/" + taskId + "/review", admin,
                "{\"fields\":[{\"key\":\"title\",\"value\":\"竞赛通知\"},{\"key\":\"category\",\"value\":\"competition\"}],\"decision\":\"approve\",\"reason\":\"x\"}", "review-dup")
                .andExpect(status().isConflict());
        // draft 关联通知有确认依据 → 可发布
        adminCommand("/admin/opportunities/" + draftId + "/publish", admin, "{\"version\":0,\"reason\":\"已人工确认\"}", "publish-once")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("published"));
        Assertions.assertEquals("published", jdbc.queryForObject("SELECT status FROM notices WHERE id=?", String.class, Long.parseLong(noticeId)));
        Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action='opportunity.publish'", Integer.class));
        // 已发布不能直接改
        adminCommand("/admin/opportunities/" + draftId + "/update", admin, "{\"title\":\"改标题\",\"version\":1}", "update-published")
                .andExpect(status().isConflict());
        // 撤回后可改
        adminCommand("/admin/opportunities/" + draftId + "/unpublish", admin, "{\"version\":1,\"reason\":\"撤下\"}", "unpublish-once")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("archived"));
        adminCommand("/admin/opportunities/" + draftId + "/update", admin, "{\"title\":\"改标题\",\"version\":2}", "update-after-unpub")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("改标题"));
    }

    @Test
    void parseWorkerFailsToManualOnBadAiResponseAndRejectRecovers() throws Exception {
        String admin = bearer(900, SessionKind.ADMIN);
        var created = new tools.jackson.databind.json.JsonMapper().readTree(
                adminCommand("/admin/notices", admin, "{\"title\":\"T\",\"content\":\"原文内容足够长原文内容足够长\"}", "notice-fail")
                        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String noticeId = created.path("data").path("id").asString();
        var parse = new tools.jackson.databind.json.JsonMapper().readTree(
                adminCommand("/admin/notices/" + noticeId + "/parse", admin, "{\"version\":0}", "parse-fail")
                        .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String taskId = parse.path("data").path("id").asString();
        ai.result = new DeepSeekClient.Completion("{\"title\":{\"value\":\"标题\",\"evidenceLocation\":\"原文没有这句话\",\"confidence\":0.9}}", "stop", "deepseek-flash", 1L);
        context.getBean("noticeParseWorker", Runnable.class).run();
        Assertions.assertEquals("failed", jdbc.queryForObject("SELECT status FROM ai_parse_tasks WHERE id=?", String.class, Long.parseLong(taskId)));
        Assertions.assertEquals("pending", jdbc.queryForObject("SELECT status FROM notices WHERE id=?", String.class, Long.parseLong(noticeId)));
        // reject 分支:先跑一个成功的解析
        ai.result = new DeepSeekClient.Completion("{\"title\":{\"value\":\"T\",\"evidenceLocation\":\"原文内容足够长\",\"confidence\":0.9}}", "stop", "deepseek-flash", 1L);
        var parse2 = new tools.jackson.databind.json.JsonMapper().readTree(
                adminCommand("/admin/notices/" + noticeId + "/parse", admin, "{\"version\":1}", "parse-two")
                        .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String task2 = parse2.path("data").path("id").asString();
        context.getBean("noticeParseWorker", Runnable.class).run();
        Assertions.assertEquals("reviewing", jdbc.queryForObject("SELECT status FROM notices WHERE id=?", String.class, Long.parseLong(noticeId)));
        adminCommand("/admin/notice-parse-tasks/" + task2 + "/review", admin,
                "{\"fields\":[{\"key\":\"title\",\"value\":\"T\"}],\"decision\":\"reject\",\"reason\":\"信息不足\"}", "review-reject")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.opportunityDraftId").doesNotExist());
        Assertions.assertEquals("pending", jdbc.queryForObject("SELECT status FROM notices WHERE id=?", String.class, Long.parseLong(noticeId)));
    }

    // ---------- 管理端:机会 ----------

    @Test
    void adminOpportunityCreateValidatesRulesAndVersionSnapshots() throws Exception {
        String admin = bearer(900, SessionKind.ADMIN);
        String draft = "{\"title\":\"科研项目\",\"category\":\"research\",\"eligibilityRules\":[{\"field\":\"college\",\"operator\":\"eq\",\"value\":\"信息学院\",\"severity\":\"hard\",\"sortOrder\":0}],\"materialRequirements\":[{\"name\":\"成绩单\",\"required\":true,\"sortOrder\":0}]}";
        var created = new tools.jackson.databind.json.JsonMapper().readTree(
                adminCommand("/admin/opportunities", admin, draft, "opp-create").andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.status").value("draft")).andReturn().getResponse().getContentAsString());
        String id = created.path("data").path("id").asString();
        Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM opportunity_rule_snapshots WHERE opportunity_id=? AND rules_version=0", Integer.class, Long.parseLong(id)));
        // 非法运算符
        adminCommand("/admin/opportunities", admin, "{\"title\":\"x\",\"category\":\"research\",\"eligibilityRules\":[{\"field\":\"college\",\"operator\":\"eval\",\"value\":\"x\",\"severity\":\"hard\"}]}", "opp-bad-op")
                .andExpect(status().isBadRequest());
        // 无 noticeId 的人工草稿直接发布,记录审计
        adminCommand("/admin/opportunities/" + id + "/publish", admin, "{\"version\":0,\"reason\":\"人工录入已确认\"}", "opp-publish")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("published"));
        mvc.perform(get("/api/v1/opportunities/" + id + "?campusCode=one")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligibilityRules.length()").value(1));
        // 撤回后改规则 → rulesVersion 提升 + 新快照,旧快照保留
        adminCommand("/admin/opportunities/" + id + "/unpublish", admin, "{\"version\":1,\"reason\":\"x\"}", "opp-unpub").andExpect(status().isOk());
        adminCommand("/admin/opportunities/" + id + "/update", admin,
                "{\"version\":2,\"eligibilityRules\":[{\"field\":\"grade\",\"operator\":\"range\",\"value\":{\"min\":\"2024\",\"max\":\"2026\"},\"severity\":\"hard\",\"sortOrder\":0}],\"materialRequirements\":[{\"name\":\"成绩单\",\"required\":true,\"sortOrder\":0}]}",
                "opp-update-rules").andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(3));
        Assertions.assertEquals(1, jdbc.queryForObject("SELECT rules_version FROM opportunities WHERE id=?", Integer.class, Long.parseLong(id)));
        Assertions.assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM opportunity_rule_snapshots WHERE opportunity_id=?", Integer.class, Long.parseLong(id)));
    }

    @Test
    void adminPublishRequiresNoticeConfirmationBasisForLinkedDrafts() throws Exception {
        String admin = bearer(900, SessionKind.ADMIN);
        var notice = new tools.jackson.databind.json.JsonMapper().readTree(
                adminCommand("/admin/notices", admin, "{\"title\":\"N\",\"content\":\"c\"}", "notice-noconfirm").andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString());
        String noticeId = notice.path("data").path("id").asString();
        var draft = new tools.jackson.databind.json.JsonMapper().readTree(
                adminCommand("/admin/opportunities", admin, "{\"title\":\"X\",\"category\":\"volunteer\",\"noticeId\":\"" + noticeId + "\"}", "opp-linked")
                        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = draft.path("data").path("id").asString();
        // 关联通知无人工确认依据 → 发布拒绝
        adminCommand("/admin/opportunities/" + id + "/publish", admin, "{\"version\":0,\"reason\":\"x\"}", "opp-pub-noconfirm")
                .andExpect(status().isUnprocessableEntity());
        // 伪造一个已确认的解析任务后可发布
        jdbc.update("INSERT INTO ai_parse_tasks(id,notice_id,status,review_decision,created_at,updated_at) VALUES(1,?,'succeeded','approved',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", Long.parseLong(noticeId));
        adminCommand("/admin/opportunities/" + id + "/publish", admin, "{\"version\":0,\"reason\":\"x\"}", "opp-pub-confirmed")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("published"));
    }

    @Test
    void adminPermissionsAndCrossCampusAreEnforced() throws Exception {
        // 学生 token 打管理端 → 401
        mvc.perform(get("/api/v1/admin/notices").header("Authorization", bearer(101, SessionKind.STUDENT)))
                .andExpect(status().isUnauthorized());
        // 无 CSRF 的管理员写 → 403
        String admin = bearer(900, SessionKind.ADMIN);
        mvc.perform(post("/api/v1/admin/notices").secure(true).header("Authorization", admin).header("Idempotency-Key", "no-csrf-key")
                .contentType("application/json").content("{\"title\":\"x\",\"content\":\"y\"}")).andExpect(status().isForbidden());
        // 外校机会管理 404
        jdbc.update("INSERT INTO opportunities(id,campus_id,title,category,status,source_type,rules_version,version,created_at,updated_at) VALUES(81,2,'外校','research','draft','manual',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        adminCommand("/admin/opportunities/81/update", admin, "{\"title\":\"x\",\"version\":0}", "opp-cross").andExpect(status().isNotFound());
        // 解析任务跨校读取 404
        mvc.perform(get("/api/v1/admin/notice-parse-tasks/999").header("Authorization", admin)).andExpect(status().isNotFound());
    }

    // ---------- 清单与预检 ----------

    @Test
    void checklistLifecycleBindsOwnMaterialVersionsAndBumpsParentVersion() throws Exception {
        student(101);
        publishedOpportunity(91, "published", null);
        jdbc.update("INSERT INTO opportunity_material_items(id,opportunity_id,name,description,required,sort_order,created_at) VALUES(1,91,'成绩单',NULL,TRUE,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO opportunity_material_items(id,opportunity_id,name,description,required,sort_order,created_at) VALUES(2,91,'证书',NULL,FALSE,1,CURRENT_TIMESTAMP)");
        String bearer = bearer(101, SessionKind.STUDENT);
        // 本人材料:file 501 + version 601
        jdbc.update("INSERT INTO material_files(id,user_id,campus_id,file_hash,size_bytes,mime_type,file_name,scan_status,status,version,created_at,updated_at) VALUES(501,101,1,'" + "a".repeat(64) + "',3,'application/pdf','f.pdf','clean','active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO material_versions(id,file_id,version_no,attachment_id,created_at) VALUES(601,501,1,NULL,CURRENT_TIMESTAMP)");
        // 创建清单:按机会材料要求复制条目
        var checklist = new tools.jackson.databind.json.JsonMapper().readTree(
                mvc.perform(put("/api/v1/opportunities/91/checklist").header("Authorization", bearer).header("Idempotency-Key", "cl-create")
                        .contentType("application/json").content("{}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2)).andReturn().getResponse().getContentAsString());
        String checklistId = checklist.path("data").path("id").asString();
        String itemId = checklist.path("data").path("items").get(0).path("id").asString();
        // 重复创建取得同一清单
        mvc.perform(put("/api/v1/opportunities/91/checklist").header("Authorization", bearer).header("Idempotency-Key", "cl-create-2")
                .contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(checklistId));
        // 绑定他人材料版本 → 拒绝
        jdbc.update("UPDATE material_files SET user_id=102 WHERE id=501");
        command("/checklists/" + checklistId + "/items/" + itemId + "/update", bearer,
                "{\"materialVersionId\":\"601\",\"version\":0}", "cl-bind-other").andExpect(status().isUnprocessableEntity());
        jdbc.update("UPDATE material_files SET user_id=101 WHERE id=501");
        // 正常绑定 → 父清单版本提升 + 使用记录
        command("/checklists/" + checklistId + "/items/" + itemId + "/update", bearer,
                "{\"materialVersionId\":\"601\",\"version\":0}", "cl-binding").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.items[0].status").value("uploaded"));
        Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM material_usage_records WHERE file_id=501 AND used_in_type='checklist_item'", Integer.class));
        // 旧版本冲突
        command("/checklists/" + checklistId + "/items/" + itemId + "/update", bearer,
                "{\"materialVersionId\":null,\"version\":0}", "cl-stale").andExpect(status().isConflict());
        // 解绑 → missing + 使用记录清除
        command("/checklists/" + checklistId + "/items/" + itemId + "/update", bearer,
                "{\"materialVersionId\":null,\"version\":1}", "cl-unbind").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("missing"));
        Assertions.assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM material_usage_records WHERE file_id=501 AND used_in_type='checklist_item'", Integer.class));
        // 他人不可读清单
        mvc.perform(get("/api/v1/checklists/" + checklistId).header("Authorization", bearer(102, SessionKind.STUDENT)))
                .andExpect(status().isNotFound());
    }

    @Test
    void singleMaterialPrecheckRunsAsyncAndChecklistPrecheckFlagsMissingAndStale() throws Exception {
        student(101);
        publishedOpportunity(95, "published", null);
        jdbc.update("INSERT INTO material_files(id,user_id,campus_id,file_hash,size_bytes,mime_type,file_name,expires_at,scan_status,status,version,object_key_encrypted,created_at,updated_at) VALUES(501,101,1,'" + "a".repeat(64) + "',3,'application/pdf','f.pdf',NULL,'clean','active',0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                crypto.encrypt("file-object-key", "clean/501"));
        jdbc.update("INSERT INTO material_versions(id,file_id,version_no,attachment_id,created_at) VALUES(601,501,1,NULL,CURRENT_TIMESTAMP)");
        storage.objects.put("clean/501", "%PDF-1.4 fake".getBytes());
        jdbc.update("INSERT INTO authorizations(id,user_id,type,status,granted_at,created_at) VALUES(1,101,'ai','granted',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        String bearer = bearer(101, SessionKind.STUDENT);
        // 缺 AI 授权 → 403
        jdbc.update("UPDATE authorizations SET status='revoked'");
        command("/materials/501/prechecks", bearer, "{\"materialVersionId\":\"601\",\"version\":0,\"aiConsentId\":\"1\"}", "pre-single-noai")
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE authorizations SET status='granted'");
        var job = new tools.jackson.databind.json.JsonMapper().readTree(
                command("/materials/501/prechecks", bearer, "{\"materialVersionId\":\"601\",\"version\":0,\"aiConsentId\":\"1\"}", "pre-single")
                        .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.jobType").value("material_precheck")).andReturn().getResponse().getContentAsString());
        context.getBean("materialPrecheckWorker", Runnable.class).run();
        mvc.perform(get("/api/v1/jobs/" + job.path("data").path("id").asString()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("succeeded"))
                .andExpect(jsonPath("$.data.result.basicComplete").value(true))
                .andExpect(jsonPath("$.data.result.results.length()").value(6));
        // 整清单预检:必交项缺失 → missing + basicComplete=false
        jdbc.update("INSERT INTO opportunity_material_items(id,opportunity_id,name,description,required,sort_order,created_at) VALUES(1,95,'成绩单',NULL,TRUE,0,CURRENT_TIMESTAMP)");
        var checklist = new tools.jackson.databind.json.JsonMapper().readTree(
                mvc.perform(put("/api/v1/opportunities/95/checklist").header("Authorization", bearer).header("Idempotency-Key", "cl-fifty95")
                        .contentType("application/json").content("{}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String checklistId = checklist.path("data").path("id").asString();
        command("/checklists/" + checklistId + "/prechecks", bearer, "{\"version\":0,\"aiConsentId\":\"1\"}", "pre-checklist")
                .andExpect(status().isAccepted());
        context.getBean("checklistPrecheckWorker", Runnable.class).run();
        mvc.perform(get("/api/v1/checklists/" + checklistId + "/prechecks/latest").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("succeeded"))
                .andExpect(jsonPath("$.data.stale").value(false))
                .andExpect(jsonPath("$.data.results[0].level").value("missing"));
        Assertions.assertEquals("needs_fix", jdbc.queryForObject("SELECT status FROM material_checklist_items WHERE checklist_id=?", String.class, Long.parseLong(checklistId)));
        // 绑定材料后旧任务 stale=true,不被覆盖
        String itemId = checklist.path("data").path("items").get(0).path("id").asString();
        command("/checklists/" + checklistId + "/items/" + itemId + "/update", bearer,
                "{\"materialVersionId\":\"601\",\"version\":0}", "cl-bind-95").andExpect(status().isOk());
        mvc.perform(get("/api/v1/checklists/" + checklistId + "/prechecks/latest").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.stale").value(true));
        // 绑定后再次预检:passed
        command("/checklists/" + checklistId + "/prechecks", bearer, "{\"version\":1,\"aiConsentId\":\"1\"}", "pre-checklist-2")
                .andExpect(status().isAccepted());
        context.getBean("checklistPrecheckWorker", Runnable.class).run();
        mvc.perform(get("/api/v1/checklists/" + checklistId + "/prechecks/latest").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.stale").value(false))
                .andExpect(jsonPath("$.data.results[0].level").value("passed"));
        Assertions.assertEquals("basic_complete", jdbc.queryForObject("SELECT status FROM material_checklist_items WHERE checklist_id=?", String.class, Long.parseLong(checklistId)));
    }

    @Test
    void jobsReadNeverLeaksPayloadOrUnscopedJobs() throws Exception {
        student(101);
        String bearer = bearer(101, SessionKind.STUDENT);
        jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,campus_id,owner_user_id,access_domain,created_at,updated_at) VALUES(701,'eligibility_check','{\"secret\":\"x\"}','pending',1,101,'student',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,campus_id,owner_user_id,access_domain,created_at,updated_at) VALUES(702,'notice_parse','{}','pending',1,900,'admin',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,created_at,updated_at) VALUES(703,'legacy','{}','pending',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/v1/jobs/701").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payload_json").doesNotExist())
                .andExpect(jsonPath("$.data.claim_token").doesNotExist());
        mvc.perform(get("/api/v1/jobs/702").header("Authorization", bearer)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/jobs/703").header("Authorization", bearer)).andExpect(status().isNotFound());
    }

    // ---------- 测试替身 ----------

    static class TestAi extends DeepSeekClient {
        volatile DeepSeekClient.Completion result;
        volatile int calls = 0;
        TestAi() { super("test-key", "deepseek-flash"); }
        @Override
        public DeepSeekClient.Completion complete(String system, String user, int maxTokens) {
            calls++;
            if (result == null) throw new AssertionError("AI result not configured");
            return result;
        }
    }

    static class TestStorage implements ObjectStorage {
        final Map<String, byte[]> objects = new HashMap<>();
        public void put(String key, java.io.InputStream stream, String mime) {
            try { objects.put(key, stream.readAllBytes()); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
        public java.io.InputStream open(String key) {
            byte[] bytes = objects.get(key);
            if (bytes == null) throw new IllegalStateException("object missing: " + key);
            return new java.io.ByteArrayInputStream(bytes);
        }
        public void delete(String key) { objects.remove(key); }
        public String sign(String key, java.time.Duration ttl) { return "https://objects.test/" + key; }
    }

    @TestConfiguration
    static class Fixtures {
        @Bean
        @Primary
        TestAi ai() { return new TestAi(); }

        @Bean
        @Primary
        TestStorage storage() { return new TestStorage(); }

        @Bean
        @Primary
        com.caizhitong.common.ratelimit.RateCounter counter() { return (bucket, window) -> 1L; }

        @Bean
        @Primary
        Clock fixtureClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }

        @Bean
        @Primary
        SessionStore store() {
            return new SessionStore() {
                final Map<String, SessionRecord> data = new HashMap<>();

                public synchronized void create(SessionRecord r) {
                    data.put(r.accessDigest(), r);
                    data.put(r.refreshDigest(), r);
                }

                public synchronized Optional<SessionRecord> find(String d, SessionKind k) {
                    return Optional.ofNullable(data.get(d)).filter(r -> r.kind() == k);
                }

                public synchronized boolean rotate(SessionRecord old, SessionRecord next) {
                    if (!data.containsKey(old.refreshDigest())) return false;
                    revoke(old);
                    create(next);
                    return true;
                }

                public synchronized boolean revoke(SessionRecord r) {
                    return data.remove(r.accessDigest()) != null & data.remove(r.refreshDigest()) != null;
                }
            };
        }
    }
}
