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
        storage.opened.clear();
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
                .andExpect(jsonPath("$.data.id").value("11"))
                .andExpect(jsonPath("$.data.sourceType").value("manual"))
                .andExpect(jsonPath("$.data.teamCount").value(0))
                .andExpect(jsonPath("$.data.isFavorited").value(false));
        mvc.perform(get("/api/v1/opportunities/13?campusCode=one")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/opportunities/14?campusCode=one")).andExpect(status().isNotFound());
    }

    @Test
    void touristWithoutCampusCodeDefaultsToFirstOpenCampus() throws Exception {
        publishedOpportunity(15, "published", null);
        jdbc.update("INSERT INTO campuses VALUES(2,'二校','two','active',NULL)");
        jdbc.update("INSERT INTO opportunities(id,campus_id,title,category,status,source_type,rules_version,version,created_at,updated_at) VALUES(16,2,'外校机会','scholarship','published','manual',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        // 文档第 2.8 节:游客省略 campusCode 默认首发开放校园(最早创建)
        mvc.perform(get("/api/v1/opportunities?limit=10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("15"));
        // 显式指定其他开放校园仍可用
        mvc.perform(get("/api/v1/opportunities?campusCode=two&limit=10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("16"));
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result.conclusion").value("eligible"))
                .andExpect(jsonPath("$.data.result.id").isString())
                .andExpect(jsonPath("$.data.result.checkedAt").isString());
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
        // 收据 id 必须是 async_jobs id(文档第 5 节 /jobs/{jobId}),解析任务 id 在任务结果里返回
        String jobId = parse.path("data").path("id").asString();
        String taskId = Long.toString(jdbc.queryForObject("SELECT id FROM ai_parse_tasks WHERE notice_id=?", Long.class, Long.parseLong(noticeId)));
        mvc.perform(get("/api/v1/jobs/" + jobId).header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobType").value("notice_parse"))
                .andExpect(jsonPath("$.data.status").value("pending"));
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
        // 收据完成并可取出解析任务 id
        mvc.perform(get("/api/v1/jobs/" + jobId).header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("succeeded"))
                .andExpect(jsonPath("$.data.result.parseTaskId").value(taskId));
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
        // 确认的适用对象必须由管理员显式补录为结构化条件。
        adminCommand("/admin/opportunities/" + draftId + "/update", admin,
                "{\"version\":0,\"eligibilityRules\":[{\"field\":\"college\",\"operator\":\"eq\",\"value\":\"信息学院\",\"severity\":\"hard\"}]}", "review-structured-rule").andExpect(status().isOk());
        adminCommand("/admin/opportunities/" + draftId + "/publish", admin, "{\"version\":1,\"reason\":\"已人工确认\"}", "publish-once")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("published"));
        Assertions.assertEquals("published", jdbc.queryForObject("SELECT status FROM notices WHERE id=?", String.class, Long.parseLong(noticeId)));
        Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action='opportunity.publish'", Integer.class));
        // 已发布不能直接改
        adminCommand("/admin/opportunities/" + draftId + "/update", admin, "{\"title\":\"改标题\",\"version\":2}", "update-published")
                .andExpect(status().isConflict());
        // 撤回后可改
        adminCommand("/admin/opportunities/" + draftId + "/unpublish", admin, "{\"version\":2,\"reason\":\"撤下\"}", "unpublish-once")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("archived"));
        adminCommand("/admin/opportunities/" + draftId + "/update", admin, "{\"title\":\"改标题\",\"version\":3}", "update-after-unpub")
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
        String taskId = Long.toString(jdbc.queryForObject("SELECT id FROM ai_parse_tasks WHERE notice_id=?", Long.class, Long.parseLong(noticeId)));
        ai.result = new DeepSeekClient.Completion("{\"title\":{\"value\":\"标题\",\"evidenceLocation\":\"原文没有这句话\",\"confidence\":0.9}}", "stop", "deepseek-flash", 1L);
        context.getBean("noticeParseWorker", Runnable.class).run();
        Assertions.assertEquals("failed", jdbc.queryForObject("SELECT status FROM ai_parse_tasks WHERE id=?", String.class, Long.parseLong(taskId)));
        Assertions.assertEquals("pending", jdbc.queryForObject("SELECT status FROM notices WHERE id=?", String.class, Long.parseLong(noticeId)));
        // 不可重试失败:收据同步 failed,不停在 pending
        Assertions.assertEquals("failed", jdbc.queryForObject("SELECT status FROM async_jobs WHERE id=(SELECT async_job_id FROM ai_parse_tasks WHERE id=?)", String.class, Long.parseLong(taskId)));
        // reject 分支:先跑一个成功的解析
        ai.result = new DeepSeekClient.Completion("{\"title\":{\"value\":\"T\",\"evidenceLocation\":\"原文内容足够长\",\"confidence\":0.9}}", "stop", "deepseek-flash", 1L);
        var parse2 = new tools.jackson.databind.json.JsonMapper().readTree(
                adminCommand("/admin/notices/" + noticeId + "/parse", admin, "{\"version\":1}", "parse-two")
                        .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String task2 = Long.toString(jdbc.queryForObject("SELECT id FROM ai_parse_tasks WHERE notice_id=? AND status IN ('pending','processing') ORDER BY id DESC LIMIT 1", Long.class, Long.parseLong(noticeId)));
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
        immutableMaterial();
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
        immutableMaterial();
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
    void checklistPrecheckFailureFinalizesJobReceiptInsteadOfHangingPending() throws Exception {
        student(101);
        publishedOpportunity(96, "published", null);
        String bearer = bearer(101, SessionKind.STUDENT);
        var checklist = new tools.jackson.databind.json.JsonMapper().readTree(
                mvc.perform(put("/api/v1/opportunities/96/checklist").header("Authorization", bearer).header("Idempotency-Key", "cl-fail96")
                        .contentType("application/json").content("{}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String checklistId = checklist.path("data").path("id").asString();
        // 构造坏输入快照 → worker 处理抛错 → 有限重试耗尽后 failed,收据同步 failed 而不是永远 pending
        jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,campus_id,owner_user_id,access_domain,retry_count,max_retries,created_at,updated_at) VALUES(801,'checklist_precheck','{}','pending',1,101,'student',0,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO precheck_tasks(id,checklist_id,user_id,async_job_id,status,checklist_version,rules_version,input_digest,input_snapshot,model_config_version,retry_count,created_at,updated_at) VALUES(1,?,101,801,'pending',0,0,'x','{\"items\":[{\"checklistItemId\":\"not-a-number\"}]}','material-precheck:v1',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                Long.parseLong(checklistId));
        for (int i = 0; i < 3; i++) {
            context.getBean("checklistPrecheckWorker", Runnable.class).run();
            jdbc.update("UPDATE precheck_tasks SET next_retry_at=NULL WHERE id=1");
        }
        Assertions.assertEquals("failed", jdbc.queryForObject("SELECT status FROM precheck_tasks WHERE id=1", String.class));
        Assertions.assertEquals("failed", jdbc.queryForObject("SELECT status FROM async_jobs WHERE id=801", String.class));
        mvc.perform(get("/api/v1/jobs/801").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("failed"))
                .andExpect(jsonPath("$.data.error.code").value("CHECKLIST_PRECHECK_FAILED"))
                .andExpect(jsonPath("$.data.error.message").isString());
        // 租约恢复路径:处理中超时且重试耗尽 → 收据同样同步 failed,不停在 pending
        jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,campus_id,owner_user_id,access_domain,retry_count,max_retries,created_at,updated_at) VALUES(802,'checklist_precheck','{}','running',1,101,'student',3,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        // 固定时钟 NOW=2026-10-15T06:00:00Z,picked_at 需早于 05:55 才构成租约过期
        jdbc.update("INSERT INTO precheck_tasks(id,checklist_id,user_id,async_job_id,status,checklist_version,rules_version,input_digest,input_snapshot,model_config_version,retry_count,picked_at,created_at,updated_at) VALUES(2,?,101,802,'running',0,0,'x','{}','material-precheck:v1',3,'2026-10-15 05:50:00',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                Long.parseLong(checklistId));
        context.getBean("checklistPrecheckWorker", Runnable.class).run();
        Assertions.assertEquals("failed", jdbc.queryForObject("SELECT status FROM precheck_tasks WHERE id=2", String.class));
        Assertions.assertEquals("failed", jdbc.queryForObject("SELECT status FROM async_jobs WHERE id=802", String.class));
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

    @Test
    void queuedHistoricalVersionReadsItsOwnPdfAfterAppend() throws Exception {
        student(101);
        immutableMaterial();
        String token = bearer(101, SessionKind.STUDENT);
        var job = data(command("/materials/501/prechecks", token,
                "{\"materialVersionId\":\"601\",\"version\":0,\"aiConsentId\":\"1\"}", "immutable-single")
                .andExpect(status().isAccepted()));
        appendSecondPdf();
        context.getBean("materialPrecheckWorker", Runnable.class).run();
        var singleResult = data(mvc.perform(get("/api/v1/jobs/" + job.path("id").asString()).header("Authorization", token))
                .andExpect(status().isOk())).path("result");
        Assertions.assertEquals("601", singleResult.path("materialVersionId").asString());
        Assertions.assertEquals(List.of("clean/v1"), storage.opened);
        Assertions.assertEquals("v1.pdf", singleResult.path("results").get(2).path("evidence").path("fileName").asString());
    }

    @Test
    void oldMissingChecklistWorkDoesNotOverwriteNewBinding() throws Exception {
        student(101);
        immutableMaterial();
        String token = bearer(101, SessionKind.STUDENT);
        var checklist = createChecklist(token, 97);
        String base = "/checklists/" + checklist.path("id").asString();
        command(base + "/prechecks", token, "{\"version\":0,\"aiConsentId\":\"1\"}", "stale-queued").andExpect(status().isAccepted());
        command(base + "/items/" + checklist.path("items").get(0).path("id").asString() + "/update", token,
                "{\"version\":0,\"materialVersionId\":\"601\"}", "stale-bind").andExpect(status().isOk());
        context.getBean("checklistPrecheckWorker", Runnable.class).run();
        Assertions.assertEquals("uploaded", data(mvc.perform(get("/api/v1" + base).header("Authorization", token)))
                .path("items").get(0).path("status").asString());
        Assertions.assertTrue(data(mvc.perform(get("/api/v1" + base + "/prechecks/latest").header("Authorization", token)))
                .path("stale").asBoolean());
    }

    @Test
    void checklistSnapshotAndWorkerKeepSelectedVersionAfterAppend() throws Exception {
        student(101);
        immutableMaterial();
        String token = bearer(101, SessionKind.STUDENT);
        var checklist = createChecklist(token, 98);
        String base = "/checklists/" + checklist.path("id").asString();
        command(base + "/items/" + checklist.path("items").get(0).path("id").asString() + "/update", token,
                "{\"version\":0,\"materialVersionId\":\"601\"}", "snapshot-bind").andExpect(status().isOk());
        appendSecondPdf();
        command(base + "/prechecks", token, "{\"version\":1,\"aiConsentId\":\"1\"}", "snapshot-queue").andExpect(status().isAccepted());
        var snapshot = new tools.jackson.databind.json.JsonMapper().readTree(jdbc.queryForObject("SELECT input_snapshot FROM precheck_tasks", String.class));
        Assertions.assertEquals("v1.pdf", snapshot.path("items").get(0).path("fileName").asString());
        context.getBean("checklistPrecheckWorker", Runnable.class).run();
        Assertions.assertEquals(List.of("clean/v1"), storage.opened);
    }

    @Test
    void revokedConsentAndUncleanSelectedAttachmentNeverReachStorage() throws Exception {
        student(101);
        immutableMaterial();
        String token = bearer(101, SessionKind.STUDENT);
        command("/materials/501/prechecks", token, "{\"version\":0,\"materialVersionId\":\"601\",\"aiConsentId\":\"1\"}", "revoke-queued")
                .andExpect(status().isAccepted());
        jdbc.update("UPDATE authorizations SET status='revoked' WHERE id=1");
        context.getBean("materialPrecheckWorker", Runnable.class).run();
        Assertions.assertTrue(storage.opened.isEmpty());
        jdbc.update("UPDATE authorizations SET status='granted' WHERE id=1");
        jdbc.update("UPDATE file_attachments SET scan_status='infected' WHERE id=701");
        command("/materials/501/prechecks", token, "{\"version\":0,\"materialVersionId\":\"601\",\"aiConsentId\":\"1\"}", "infected-queue")
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void finalParseLeaseExpiryRestoresOnlyLatestNoticeAndAllowsRetry() throws Exception {
        String admin = bearer(900, SessionKind.ADMIN);
        var notice = data(adminCommand("/admin/notices", admin, "{\"title\":\"N\",\"content\":\"fixture\"}", "lease-notice"));
        long noticeId = Long.parseLong(notice.path("id").asString());
        adminCommand("/admin/notices/" + noticeId + "/parse", admin, "{\"version\":0}", "lease-parse").andExpect(status().isAccepted());
        jdbc.update("UPDATE ai_parse_tasks SET status='processing',retry_count=3,picked_at='2026-10-15 05:50:00',claim_token='crashed'");
        context.getBean("noticeParseWorker", Runnable.class).run();
        Assertions.assertEquals("failed", jdbc.queryForObject("SELECT status FROM ai_parse_tasks", String.class));
        Assertions.assertEquals("failed", jdbc.queryForObject("SELECT status FROM async_jobs WHERE job_type='notice_parse'", String.class));
        Assertions.assertEquals("pending", jdbc.queryForObject("SELECT status FROM notices", String.class));
        adminCommand("/admin/notices/" + noticeId + "/parse", admin, "{\"version\":1}", "lease-retry").andExpect(status().isAccepted());
        jdbc.update("UPDATE ai_parse_tasks SET status='processing',retry_count=3,picked_at='2026-10-15 05:50:00' WHERE status='failed'");
        jdbc.update("UPDATE ai_parse_tasks SET next_retry_at='2026-10-15 07:00:00' WHERE status='pending'");
        context.getBean("noticeParseWorker", Runnable.class).run();
        Assertions.assertEquals("parsing", jdbc.queryForObject("SELECT status FROM notices", String.class));
    }

    @Test
    void actualMissingKeyAdapterErrorKeepsItsPublicCode() throws Exception {
        String admin = bearer(900, SessionKind.ADMIN);
        String id = data(adminCommand("/admin/notices", admin, "{\"title\":\"N\",\"content\":\"fixture\"}", "key-notice")).path("id").asString();
        adminCommand("/admin/notices/" + id + "/parse", admin, "{\"version\":0}", "key-parse").andExpect(status().isAccepted());
        new com.caizhitong.ai.notice.NoticeParseWorker(jdbc, context.getBean(com.caizhitong.common.SnowflakeIds.class),
                context.getBean(Clock.class), context.getBean(tools.jackson.databind.json.JsonMapper.class),
                new DeepSeekClient("", "deepseek-flash"), context.getBean(com.caizhitong.integration.notice.SafeUrlReader.class),
                context.getBean(com.caizhitong.material.files.AttachmentService.class), context.getBean(ActorLoader.class),
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).run();
        Assertions.assertEquals("AI_KEY_MISSING", jdbc.queryForObject("SELECT error FROM async_jobs WHERE job_type='notice_parse'", String.class));
    }

    @Test
    void invalidDraftAndMissingVersionsReturn400WithoutWrites() throws Exception {
        String admin = bearer(900, SessionKind.ADMIN);
        for (String body : List.of("{\"title\":\"N\"}", "{\"title\":\"N\",\"category\":null}",
                "{\"title\":\"N\",\"category\":\"research\",\"materialRequirements\":[{\"name\":\"PDF\",\"required\":\"false\"}]}",
                "{\"title\":\"N\",\"category\":\"research\",\"materialRequirements\":{}}"))
            adminCommand("/admin/opportunities", admin, body, "invalid-draft-" + body.length()).andExpect(status().isBadRequest());
        Assertions.assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM opportunities", Integer.class));
        publishedOpportunity(93, "draft", null);
        adminCommand("/admin/opportunities/93/publish", admin, "{\"reason\":\"reviewed\"}", "missing-publish-v").andExpect(status().isBadRequest());
        String notice = data(adminCommand("/admin/notices", admin, "{\"title\":\"N\",\"content\":\"fixture\"}", "missing-version-notice")).path("id").asString();
        adminCommand("/admin/notices/" + notice + "/parse", admin, "{}", "missing-parse-v").andExpect(status().isBadRequest());
    }

    @Test
    void confirmedFreeTextBlocksEmptyDraftUntilHumanStructuredEditing() throws Exception {
        String admin = bearer(900, SessionKind.ADMIN);
        long notice = Long.parseLong(data(adminCommand("/admin/notices", admin, "{\"title\":\"N\",\"content\":\"fixture\"}", "confirmed-text-notice")).path("id").asString());
        jdbc.update("UPDATE notices SET status='reviewing' WHERE id=?", notice);
        jdbc.update("INSERT INTO ai_parse_tasks(id,notice_id,status,created_at,updated_at) VALUES(1,?,'succeeded',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", notice);
        String draft = data(adminCommand("/admin/notice-parse-tasks/1/review", admin,
                "{\"fields\":[{\"key\":\"title\",\"value\":\"N\"},{\"key\":\"category\",\"value\":\"research\"},{\"key\":\"conditions\",\"value\":\"信息学院学生\"},{\"key\":\"materials\",\"value\":\"成绩单和申请书\"}],\"decision\":\"approve\",\"reason\":\"人工确认\"}", "confirmed-text-review")).path("opportunityDraftId").asString();
        var fields = data(mvc.perform(get("/api/v1/admin/notice-parse-tasks/1").header("Authorization", admin))).path("fields");
        Assertions.assertTrue(fields.toString().contains("成绩单和申请书"));
        adminCommand("/admin/opportunities/" + draft + "/publish", admin, "{\"version\":0,\"reason\":\"reviewed\"}", "empty-confirmed-publish").andExpect(status().isUnprocessableEntity());
        adminCommand("/admin/opportunities/" + draft + "/update", admin,
                "{\"version\":0,\"eligibilityRules\":[{\"field\":\"college\",\"operator\":\"eq\",\"value\":\"信息学院\",\"severity\":\"hard\"}],\"materialRequirements\":[{\"name\":\"成绩单\",\"required\":true},{\"name\":\"申请书\",\"required\":true}]}", "structured-confirmed-edit").andExpect(status().isOk());
        adminCommand("/admin/opportunities/" + draft + "/publish", admin, "{\"version\":1,\"reason\":\"逐项补录\"}", "structured-confirmed-publish").andExpect(status().isOk());
    }

    @Test
    void humanCanExplicitlyResolveUniversalAudienceAndNoExtraMaterialsWithEmptyArrays() throws Exception {
        String admin = bearer(900, SessionKind.ADMIN);
        long notice = Long.parseLong(data(adminCommand("/admin/notices", admin, "{\"title\":\"N\",\"content\":\"全体学生，无须额外材料\"}", "empty-resolution-notice")).path("id").asString());
        jdbc.update("UPDATE notices SET status='reviewing' WHERE id=?", notice);
        jdbc.update("INSERT INTO ai_parse_tasks(id,notice_id,status,created_at,updated_at) VALUES(1,?,'succeeded',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", notice);
        String draft = data(adminCommand("/admin/notice-parse-tasks/1/review", admin,
                "{\"fields\":[{\"key\":\"title\",\"value\":\"N\"},{\"key\":\"category\",\"value\":\"research\"},{\"key\":\"targetAudience\",\"value\":\"全体学生\"},{\"key\":\"materials\",\"value\":\"无须额外材料\"}],\"decision\":\"approve\",\"reason\":\"人工确认\"}", "empty-resolution-review")).path("opportunityDraftId").asString();
        adminCommand("/admin/opportunities/" + draft + "/publish", admin, "{\"version\":0,\"reason\":\"尚未处理\"}", "empty-resolution-blocked").andExpect(status().isUnprocessableEntity());
        adminCommand("/admin/opportunities/" + draft + "/update", admin, "{\"version\":0,\"eligibilityRules\":[],\"materialRequirements\":[]}", "empty-resolution-edit").andExpect(status().isOk());
        adminCommand("/admin/opportunities/" + draft + "/publish", admin, "{\"version\":1,\"reason\":\"原文明确无附加条件和材料，已人工核对\"}", "empty-resolution-publish").andExpect(status().isOk());
        Assertions.assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM opportunity_rule_snapshots", Integer.class));
    }

    tools.jackson.databind.JsonNode data(ResultActions action) throws Exception {
        return new tools.jackson.databind.json.JsonMapper().readTree(action.andReturn().getResponse().getContentAsString()).path("data");
    }

    @Test
    void validationRulesPersistIntoChecklistAndPrecheckAndAreAppliedToSelectedVersion() throws Exception {
        student(101);
        immutableMaterial();
        String admin = bearer(900, SessionKind.ADMIN);
        String requirement = "{\"name\":\"指定PDF\",\"required\":true,\"validationRules\":{\"mimeTypes\":[\"application/pdf\"],\"maxSizeBytes\":1,\"fileNameContains\":\"成绩单\"}}";
        String opportunity = data(adminCommand("/admin/opportunities", admin,
                "{\"title\":\"规则测试\",\"category\":\"research\",\"materialRequirements\":[" + requirement + "]}", "rules-draft")).path("id").asString();
        adminCommand("/admin/opportunities/" + opportunity + "/publish", admin, "{\"version\":0,\"reason\":\"已核对\"}", "rules-publish").andExpect(status().isOk());
        var requirements = data(mvc.perform(get("/api/v1/opportunities/" + opportunity + "?campusCode=one"))).path("materialRequirements");
        Assertions.assertEquals(1, requirements.get(0).path("validationRules").path("maxSizeBytes").asInt());
        String token = bearer(101, SessionKind.STUDENT);
        var checklist = data(mvc.perform(put("/api/v1/opportunities/" + opportunity + "/checklist").header("Authorization", token)
                .header("Idempotency-Key", "rules-checklist").contentType("application/json").content("{}")).andExpect(status().isOk()));
        String base = "/checklists/" + checklist.path("id").asString();
        command(base + "/items/" + checklist.path("items").get(0).path("id").asString() + "/update", token,
                "{\"version\":0,\"materialVersionId\":\"601\"}", "rules-bind").andExpect(status().isOk());
        command(base + "/prechecks", token, "{\"version\":1,\"aiConsentId\":\"1\"}", "rules-queue").andExpect(status().isAccepted());
        var snapshot = new tools.jackson.databind.json.JsonMapper().readTree(jdbc.queryForObject("SELECT input_snapshot FROM precheck_tasks", String.class));
        Assertions.assertEquals("成绩单", snapshot.path("items").get(0).path("validationRules").path("fileNameContains").asString());
        context.getBean("checklistPrecheckWorker", Runnable.class).run();
        var results = data(mvc.perform(get("/api/v1" + base + "/prechecks/latest").header("Authorization", token))).path("results");
        Assertions.assertTrue(results.toString().contains("missing"));
        Assertions.assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM precheck_results WHERE level='missing'", Integer.class));
        Assertions.assertTrue(jdbc.queryForObject("SELECT material_requirements_json FROM opportunity_rule_snapshots", String.class).contains("fileNameContains"));
    }

    @Test
    void invalidValidationRulesAreRejectedInsteadOfIgnored() throws Exception {
        String admin = bearer(900, SessionKind.ADMIN);
        int index = 0;
        for (String rules : List.of("{\"unknown\":true}", "{\"mimeTypes\":[\"text/plain\"]}", "{\"mimeTypes\":\"application/pdf\"}",
                "{\"maxSizeBytes\":0}", "{\"maxSizeBytes\":20971521}", "{\"maxSizeBytes\":\"2\"}", "{\"fileNameContains\":\"\"}", "[]")) {
            adminCommand("/admin/opportunities", admin, "{\"title\":\"N\",\"category\":\"research\",\"materialRequirements\":[{\"name\":\"PDF\",\"required\":true,\"validationRules\":" + rules + "}]}", "invalid-rules-" + index++)
                    .andExpect(status().isBadRequest());
        }
        Assertions.assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM opportunities", Integer.class));
    }

    @Test
    void olderTaskCannotOverwriteLaterCompletedTaskForSameChecklistVersion() throws Exception {
        student(101); immutableMaterial();
        String token = bearer(101, SessionKind.STUDENT);
        var checklist = createChecklist(token, 99);
        String base = "/checklists/" + checklist.path("id").asString();
        command(base + "/items/" + checklist.path("items").get(0).path("id").asString() + "/update", token,
                "{\"version\":0,\"materialVersionId\":\"601\"}", "ordering-bind").andExpect(status().isOk());
        command(base + "/prechecks", token, "{\"version\":1,\"aiConsentId\":\"1\"}", "ordering-first").andExpect(status().isAccepted());
        command(base + "/prechecks", token, "{\"version\":1,\"aiConsentId\":\"1\"}", "ordering-second").andExpect(status().isAccepted());
        long older = jdbc.queryForObject("SELECT MIN(id) FROM precheck_tasks", Long.class);
        jdbc.update("UPDATE precheck_tasks SET status='running',picked_at='2026-10-15 06:00:00' WHERE id=?", older);
        jdbc.update("UPDATE file_attachments SET scan_status='pending' WHERE id=701");
        // Actual later work completes while the older lease is still outstanding.
        context.getBean("checklistPrecheckWorker", Runnable.class).run();
        Assertions.assertEquals("needs_fix", jdbc.queryForObject("SELECT status FROM material_checklist_items", String.class));
        jdbc.update("UPDATE file_attachments SET scan_status='clean' WHERE id=701");
        jdbc.update("UPDATE precheck_tasks SET status='pending' WHERE id=?", older);
        context.getBean("checklistPrecheckWorker", Runnable.class).run();
        Assertions.assertEquals("needs_fix", jdbc.queryForObject("SELECT status FROM material_checklist_items", String.class));
        Assertions.assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM precheck_tasks WHERE status='succeeded'", Integer.class));
    }

    @Test
    void exhaustedChecklistLeaseRestoresUploadedItemsForRetry() throws Exception {
        student(101); immutableMaterial();
        String token = bearer(101, SessionKind.STUDENT);
        var checklist = createChecklist(token, 100);
        String base = "/checklists/" + checklist.path("id").asString();
        command(base + "/items/" + checklist.path("items").get(0).path("id").asString() + "/update", token,
                "{\"version\":0,\"materialVersionId\":\"601\"}", "failed-restore-bind").andExpect(status().isOk());
        command(base + "/prechecks", token, "{\"version\":1,\"aiConsentId\":\"1\"}", "failed-restore-queue").andExpect(status().isAccepted());
        jdbc.update("UPDATE precheck_tasks SET status='running',retry_count=3,picked_at='2026-10-15 05:50:00'");
        context.getBean("checklistPrecheckWorker", Runnable.class).run();
        Assertions.assertEquals("uploaded", jdbc.queryForObject("SELECT status FROM material_checklist_items", String.class));
    }

    @Test
    void nonNullExpiryIsSnapshottedAndProducesConfirmation() throws Exception {
        student(101); immutableMaterial();
        jdbc.update("UPDATE material_files SET expires_at='2026-10-14 06:00:00' WHERE id=501");
        String token = bearer(101, SessionKind.STUDENT);
        var job = data(command("/materials/501/prechecks", token,
                "{\"version\":0,\"materialVersionId\":\"601\",\"aiConsentId\":\"1\"}", "non-null-expiry").andExpect(status().isAccepted()));
        context.getBean("materialPrecheckWorker", Runnable.class).run();
        var result = data(mvc.perform(get("/api/v1/jobs/" + job.path("id").asString()).header("Authorization", token))).path("result");
        Assertions.assertEquals("confirm", result.path("results").get(4).path("level").asString());
        Assertions.assertEquals("2026-10-14T06:00:00Z", result.path("results").get(4).path("evidence").path("expiresAt").asString());
    }

    @Test
    void parseCompletionWaitsForNoticeWithoutHoldingTaskLock() throws Exception {
        assertParseWriteWaitsForNoticeFirst(false);
    }

    @Test
    void parseFailureWaitsForNoticeWithoutHoldingTaskLock() throws Exception {
        assertParseWriteWaitsForNoticeFirst(true);
    }

    void assertParseWriteWaitsForNoticeFirst(boolean fail) throws Exception {
        String admin = bearer(900, SessionKind.ADMIN);
        String notice = data(adminCommand("/admin/notices", admin, "{\"title\":\"N\",\"content\":\"fixture\"}", "lock-order-notice")).path("id").asString();
        adminCommand("/admin/notices/" + notice + "/parse", admin, "{\"version\":0}", "lock-order-parse").andExpect(status().isAccepted());
        long task = jdbc.queryForObject("SELECT id FROM ai_parse_tasks", Long.class);
        var attempted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        // Execute the real JDBC locks; the gates only control which transaction advances first.
        JdbcTemplate observed = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
                var rows = super.queryForList(sql, type, args);
                if (sql.contains("status='processing' AND claim_token=? FOR UPDATE")) {
                    attempted.countDown();
                    try {
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test gate timed out");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
                return rows;
            }
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.equals("SELECT id FROM notices WHERE id=? FOR UPDATE")) attempted.countDown();
                return super.queryForList(sql, args);
            }
        };
        ai.result = new DeepSeekClient.Completion("{\"title\":{\"value\":\"N\",\"evidenceLocation\":\"fixture\"}}", "stop", "fixture", 1);
        var tm = context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
        var worker = new com.caizhitong.ai.notice.NoticeParseWorker(observed, context.getBean(com.caizhitong.common.SnowflakeIds.class),
                context.getBean(Clock.class), context.getBean(tools.jackson.databind.json.JsonMapper.class), fail ? new DeepSeekClient("", "fixture") : ai,
                context.getBean(com.caizhitong.integration.notice.SafeUrlReader.class), context.getBean(com.caizhitong.material.files.AttachmentService.class),
                context.getBean(ActorLoader.class), tm);
        var taskLockFree = new java.util.concurrent.atomic.AtomicBoolean();
        var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(s -> {
                // Recovery/notice writers hold this same parent row before inspecting the parse task.
                jdbc.queryForList("SELECT id FROM notices WHERE id=? FOR UPDATE", Long.parseLong(notice));
                future.set(executor.submit(worker));
                try {
                    Assertions.assertTrue(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS), "worker never reached completion/failure lock");
                    try {
                        jdbc.queryForList("SELECT id FROM ai_parse_tasks WHERE id=? FOR UPDATE NOWAIT", task);
                        taskLockFree.set(true);
                    } catch (org.springframework.dao.CannotAcquireLockException | org.springframework.dao.QueryTimeoutException expectedContention) {
                        taskLockFree.set(false);
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                finally { release.countDown(); }
            });
            future.get().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { release.countDown(); }
        Assertions.assertTrue(taskLockFree.get(), "worker held task while waiting for notice, exposing the inverse-lock deadlock");
        Assertions.assertEquals(fail ? "failed" : "succeeded", jdbc.queryForObject("SELECT status FROM ai_parse_tasks", String.class));
        Assertions.assertEquals(fail ? "pending" : "reviewing", jdbc.queryForObject("SELECT status FROM notices", String.class));
        Assertions.assertEquals(fail ? 0 : 1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_parse_fields", Integer.class));
    }

    tools.jackson.databind.JsonNode createChecklist(String token, long opportunity) throws Exception {
        publishedOpportunity(opportunity, "published", null);
        jdbc.update("INSERT INTO opportunity_material_items(id,opportunity_id,name,required,sort_order,created_at) VALUES(1,?,'PDF',TRUE,0,CURRENT_TIMESTAMP)", opportunity);
        return data(mvc.perform(put("/api/v1/opportunities/" + opportunity + "/checklist").header("Authorization", token)
                .header("Idempotency-Key", "checklist-fixture-" + opportunity).contentType("application/json").content("{}")).andExpect(status().isOk()));
    }

    void immutableMaterial() throws Exception {
        pdfAttachment(701, "clean/v1", "v1.pdf", "first PDF");
        jdbc.update("INSERT INTO material_files(id,user_id,campus_id,current_attachment_id,file_hash,size_bytes,mime_type,file_name,scan_status,status,version,object_key_encrypted,created_at,updated_at) VALUES(501,101,1,701,?,?,'application/pdf','v1.pdf','clean','active',0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "a".repeat(64), storage.objects.get("clean/v1").length, crypto.encrypt("file-object-key", "clean/v1"));
        jdbc.update("INSERT INTO material_versions(id,file_id,version_no,attachment_id,object_key_encrypted,file_hash,created_at) VALUES(601,501,1,701,?,(SELECT content_sha256 FROM file_attachments WHERE id=701),CURRENT_TIMESTAMP)", crypto.encrypt("file-object-key", "clean/v1"));
        jdbc.update("INSERT INTO authorizations(id,user_id,type,status,granted_at,created_at) VALUES(1,101,'ai','granted',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }

    void appendSecondPdf() throws Exception {
        pdfAttachment(702, "clean/v2", "v2.pdf", "different second PDF");
        jdbc.update("INSERT INTO material_versions(id,file_id,version_no,attachment_id,object_key_encrypted,file_hash,created_at) VALUES(602,501,2,702,?,(SELECT content_sha256 FROM file_attachments WHERE id=702),CURRENT_TIMESTAMP)", crypto.encrypt("file-object-key", "clean/v2"));
        jdbc.update("UPDATE material_files SET current_attachment_id=702,object_key_encrypted=?,file_name='v2.pdf',size_bytes=?,version=1 WHERE id=501", crypto.encrypt("file-object-key", "clean/v2"), storage.objects.get("clean/v2").length);
    }

    void pdfAttachment(long id, String key, String name, String text) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.showText(text); stream.endText();
            }
            doc.save(bytes);
        }
        storage.objects.put(key, bytes.toByteArray());
        jdbc.update("INSERT INTO file_attachments(id,campus_id,owner_user_id,access_domain,purpose,file_name,mime_type,size_bytes,content_sha256,object_key_encrypted,scan_status,state,created_at,updated_at) VALUES(?,1,101,'student','material',?,'application/pdf',?,?,?,'clean','active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, name, bytes.size(), HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())), crypto.encrypt("file-object-key", key));
    }

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
        final List<String> opened = new ArrayList<>();
        public void put(String key, java.io.InputStream stream, String mime) {
            try { objects.put(key, stream.readAllBytes()); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
        public java.io.InputStream open(String key) {
            opened.add(key);
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
