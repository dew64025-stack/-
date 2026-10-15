package com.caizhitong.identity;

import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.security.*;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:identity_http;MODE=MySQL;DB_CLOSE_DELAY=-1","app.wechat.app-id=test-app"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(IdentityHttpTest.Fixtures.class)
@Sql(scripts="/identity-schema.sql",executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class IdentityHttpTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired SessionTokenService sessions;
    @Autowired SensitiveDataCrypto crypto;
    @Autowired com.caizhitong.integration.wechat.WechatGateway gateway;
    @Autowired OneTimeSecrets secrets;
    @Autowired org.springframework.context.ApplicationContext context;
    @BeforeEach void seed() {
        for (String table : List.of("api_idempotency_records","admin_audit_logs","async_jobs","student_roster","student_verifications","user_profiles","authorizations","user_phones","user_wechat_identities","user_roles","roles","admin_credentials","users","campus_regions","campus_organizations","campus_configs","campuses")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO campuses VALUES(1,'大学一','one','active',NULL),(2,'大学二','two','active',NULL),(3,'关闭校园','closed','disabled',NULL)");
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(101,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(102,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(201,2,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(900,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO roles(id,code) VALUES(7,'campus_admin'),(4,'content_moderator'),(8,'platform_admin'),(9,'security_investigator')");
        jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id) VALUES(1,900,7,1)");
        jdbc.update("INSERT INTO campus_organizations(id,campus_id,name,type,status) VALUES(1,1,'计算机学院','college','active'),(2,2,'其他学院','college','active')");
        jdbc.update("INSERT INTO campus_regions(id,campus_id,name,type,status) VALUES(1,1,'一号楼','building','active'),(2,2,'二号楼','building','active')");
        jdbc.update("INSERT INTO campus_configs VALUES(1,1,'profile.allowedGrades','[\"2026\"]')");
    }
    String bearer(long user, long campus, SessionKind kind) { return "Bearer "+sessions.issue(user,campus,kind).accessToken(); }
    ResultActions write(String path,String bearer,String body,String key) throws Exception {
        var request=post("/api/v1"+path).header("Authorization",bearer).header("Idempotency-Key",key).contentType("application/json").content(body);
        if(path.startsWith("/admin/")){var bootstrap=mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();String token=new tools.jackson.databind.json.JsonMapper().readTree(bootstrap.getResponse().getContentAsString()).path("data").path("token").asString();request.secure(true).cookie(bootstrap.getResponse().getCookie("ADMIN-XSRF-TOKEN")).header("X-ADMIN-CSRF",token);}
        return mvc.perform(request);
    }
    void phone(long user) {
        jdbc.update("INSERT INTO user_phones(id,user_id,phone_encrypted,phone_search,verified,created_at) VALUES(?,?,?,?,1,CURRENT_TIMESTAMP)",user,user,crypto.encrypt("phone","+861380000"+user),HexFormat.of().formatHex(crypto.searchHash("phone","+861380000"+user)));
        jdbc.update("INSERT INTO authorizations(id,user_id,type,status,granted_at,created_at) VALUES(?,?,'phone','granted',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user,user);
    }
    @Test void campusReadsUseServerCampusAndHideClosedCampuses() throws Exception {
        mvc.perform(get("/api/v1/campuses")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/v1/campus/regions?campusCode=two").header("Authorization",bearer(101,1,SessionKind.STUDENT))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("1"));
        mvc.perform(get("/api/v1/campus/organizations?campusCode=one&type=college")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("计算机学院"));
    }
    @Test void profilesStartEmptyAndUseTheirOwnVersion() throws Exception {
        String token=bearer(101,1,SessionKind.STUDENT);
        mvc.perform(get("/api/v1/me/profile").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.skills").isEmpty()).andExpect(jsonPath("$.data.version").value(0));
        write("/me/profile/update",token,"{\"skills\":[\"Java\",\"Java\"],\"college\":\"计算机学院\",\"grade\":\"2026\",\"version\":0}","profile-first").andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.skills.length()").value(1));
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT version FROM users WHERE id=101",Integer.class));
        write("/me/profile/update",token,"{\"grade\":\"2026\",\"version\":0}","profile-stale").andExpect(status().isConflict());
    }
    @Test void strictDtoRejectsCoercionUnknownFieldsAndEmptyUpdates() throws Exception {
        String token=bearer(101,1,SessionKind.STUDENT);
        for(String body:List.of("{\"skills\":[1],\"version\":0}","{\"interests\":[null],\"version\":0}","{\"college\":true,\"version\":0}","{\"skills\":[],\"version\":\"0\"}","{\"skills\":[],\"version\":0.0}","{\"skills\":[],\"version\":-1}","{\"userId\":\"102\",\"version\":0}","{\"version\":0}")) write("/me/profile/update",token,body,UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        for(String body:List.of("{\"avatarAttachmentId\":1,\"version\":0}","{\"nickname\":null,\"version\":0}","{\"version\":0}")) write("/me/update",token,body,UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/wechat/login").contentType("application/json").content("{\"code\":2,\"campusCode\":\"one\"}")).andExpect(status().isBadRequest());
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_profiles",Integer.class));
    }
    @Test void profileValuesMustComeFromOwnCampusConfiguration() throws Exception {
        String token=bearer(101,1,SessionKind.STUDENT);
        write("/me/profile/update",token,"{\"college\":\"其他学院\",\"version\":0}","wrong-college").andExpect(status().isBadRequest());
        write("/me/profile/update",token,"{\"grade\":\"2027\",\"version\":0}","wrong-grade").andExpect(status().isBadRequest());
    }
    @Test void nicknameLongerThanThirtyTwoCharactersDoesNotWriteUser() throws Exception {
        String body=new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of("nickname","名".repeat(33),"version",0));
        write("/me/update",bearer(101,1,SessionKind.STUDENT),body,"nickname-too-long").andExpect(status().isBadRequest());
        Assertions.assertNull(jdbc.queryForObject("SELECT nickname FROM users WHERE id=101",String.class));
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT version FROM users WHERE id=101",Integer.class));
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency_records",Integer.class));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"skills","interests"})
    void profileArraysWithTwentyOneItemsDoNotWriteProfile(String field) throws Exception {
        List<String> items=java.util.stream.IntStream.range(0,21).mapToObj(i->"项目"+i).toList();
        String body=new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of(field,items,"version",0));
        write("/me/profile/update",bearer(101,1,SessionKind.STUDENT),body,"too-many-"+field).andExpect(status().isBadRequest());
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_profiles",Integer.class));
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency_records",Integer.class));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"skills","interests"})
    void profileItemsLongerThanThirtyTwoCharactersDoNotWriteProfile(String field) throws Exception {
        String body=new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of(field,List.of("项".repeat(33)),"version",0));
        write("/me/profile/update",bearer(101,1,SessionKind.STUDENT),body,"item-too-long-"+field).andExpect(status().isBadRequest());
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_profiles",Integer.class));
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency_records",Integer.class));
    }
    @Test void nicknameAtThirtyTwoCharactersPersists() throws Exception {
        String nickname="名".repeat(32),body=new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of("nickname",nickname,"version",0));
        write("/me/update",bearer(101,1,SessionKind.STUDENT),body,"nickname-at-limit").andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value(nickname));
        Assertions.assertEquals(nickname,jdbc.queryForObject("SELECT nickname FROM users WHERE id=101",String.class));
        Assertions.assertEquals(1,jdbc.queryForObject("SELECT version FROM users WHERE id=101",Integer.class));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"skills","interests"})
    void profileArraysAtTwentyItemsAndThirtyTwoCharactersPersist(String field) throws Exception {
        List<String> items=java.util.stream.IntStream.range(0,20).mapToObj(i->"项".repeat(30)+String.format("%02d",i)).toList();
        var mapper=new tools.jackson.databind.json.JsonMapper();String body=mapper.writeValueAsString(Map.of(field,items,"version",0));
        write("/me/profile/update",bearer(101,1,SessionKind.STUDENT),body,"profile-at-limit-"+field).andExpect(status().isOk()).andExpect(jsonPath("$.data."+field+".length()").value(20)).andExpect(jsonPath("$.data."+field+"[19]").value(items.get(19)));
        Assertions.assertEquals(mapper.writeValueAsString(items),jdbc.queryForObject("SELECT "+field+" FROM user_profiles WHERE user_id=101",String.class));
        Assertions.assertEquals(1,jdbc.queryForObject("SELECT version FROM user_profiles WHERE user_id=101",Integer.class));
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT version FROM users WHERE id=101",Integer.class));
    }
    @Test void unboundPhoneCannotApplyAndUnknownRosterStillCreatesPending() throws Exception {
        String token=bearer(101,1,SessionKind.STUDENT);
        write("/student-verifications",token,"{\"studentNumber\":\"20260001\"}","student-unbound").andExpect(status().isForbidden());
        phone(101);
        write("/student-verifications",token,"{\"studentNumber\":\"20260001\"}","student-pending").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("pending"));
        mvc.perform(get("/api/v1/me/student-verification").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.studentNumberMasked").value("****0001"));
        Assertions.assertFalse(sessions.authenticate(token.substring(7),SessionKind.STUDENT).orElseThrow().studentVerified());
    }
    @Test void rejectedStudentCanApplyAgainAndDecisionIsAuditedAtomically() throws Exception {
        phone(101);String student=bearer(101,1,SessionKind.STUDENT),admin=bearer(900,1,SessionKind.ADMIN);
        write("/student-verifications",student,"{\"studentNumber\":\"20260001\"}","apply-original").andExpect(status().isOk());
        long id=jdbc.queryForObject("SELECT id FROM student_verifications",Long.class);
        write("/admin/student-verifications/"+id+"/decision",admin,"{\"decision\":\"reject\",\"reason\":\"请补资料\",\"version\":0}","reject-original").andExpect(status().isOk());
        write("/student-verifications",student,"{\"studentNumber\":\"20260001\"}","apply-second").andExpect(status().isOk());
        long second=jdbc.queryForObject("SELECT id FROM student_verifications WHERE status='pending'",Long.class);
        write("/admin/student-verifications/"+second+"/decision",admin,"{\"decision\":\"approve\",\"reason\":\"已人工核验\",\"version\":0}","approve-second").andExpect(status().isOk());
        Assertions.assertTrue(sessions.authenticate(student.substring(7),SessionKind.STUDENT).orElseThrow().studentVerified());
        Assertions.assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs",Integer.class));
    }
    @Test void crossCampusReviewAndUnavailableFilesAreDenied() throws Exception {
        phone(201);String student=bearer(201,2,SessionKind.STUDENT),admin=bearer(900,1,SessionKind.ADMIN);
        write("/student-verifications",student,"{\"studentNumber\":\"20260001\"}","other-apply").andExpect(status().isOk());
        long id=jdbc.queryForObject("SELECT id FROM student_verifications",Long.class);
        write("/admin/student-verifications/"+id+"/decision",admin,"{\"decision\":\"approve\",\"reason\":\"核验\",\"version\":0}","other-review").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        write("/admin/student-verifications/999999/decision",admin,"{\"decision\":\"approve\",\"reason\":\"核验\",\"version\":0}","missing-review").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        write("/me/update",bearer(101,1,SessionKind.STUDENT),"{\"avatarAttachmentId\":\"500\",\"version\":0}","file-avatar").andExpect(status().isServiceUnavailable());
        write("/admin/student-roster/imports",admin,"{\"attachmentId\":\"500\"}","file-roster").andExpect(status().isServiceUnavailable());
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs",Integer.class));
    }
    @Test void credentialLoginNeedsCsrfAndWrongPasswordDoesNotIssueSession() throws Exception {
        mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isOk()).andExpect(cookie().secure("ADMIN-XSRF-TOKEN",true)).andExpect(cookie().httpOnly("ADMIN-XSRF-TOKEN",true));
        mvc.perform(post("/api/v1/admin/auth/login").contentType("application/json").content("{\"username\":\"missing\",\"password\":\"invalid\"}")).andExpect(status().isForbidden());
        write("/admin/auth/login","","{\"username\":\"missing\",\"password\":\"invalid\"}","admin-wrong-pass").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data").doesNotExist());
        write("/admin/auth/login","","{\"username\":\"missing\",\"password\":\""+"汉".repeat(26)+"\"}","admin-password-bytes").andExpect(status().isBadRequest());
    }
    @Test void roleElevationAndSensitiveQueueAreRestricted() throws Exception {
        String admin=bearer(900,1,SessionKind.ADMIN);
        mvc.perform(get("/api/v1/admin/student-verifications").header("Authorization",admin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/users").header("Authorization",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].phoneEncrypted").doesNotExist());
        write("/admin/users/101/roles",admin,"{\"roleCode\":\"platform_admin\",\"campusId\":\"1\",\"reauthToken\":\"invalid\",\"reason\":\"申请\"}","role-elevation").andExpect(status().isForbidden());
    }
    @Test void serverWechatIdentityIsUniqueCaseSensitiveAndCampusCannotChange() throws Exception {
        String original=login("CaseA","one");
        login("CaseA","two");
        Assertions.assertEquals(1,jdbc.queryForObject("SELECT campus_id FROM users WHERE id=(SELECT user_id FROM user_wechat_identities WHERE openid='CaseA')",Integer.class));
        login("casea","one");
        Assertions.assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM user_wechat_identities",Integer.class));
        mvc.perform(post("/api/v1/auth/wechat/login").contentType("application/json").content("{\"code\":\"code-CaseA-one\",\"campusCode\":\"one\"}")).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+original)).andExpect(status().isOk()).andExpect(jsonPath("$.data.campusId").value("1"));
    }
    String login(String identity,String campus) throws Exception {
        var result=mvc.perform(post("/api/v1/auth/wechat/login").contentType("application/json").content("{\"code\":\"code-"+identity+"-"+campus+"\",\"campusCode\":\""+campus+"\"}")).andExpect(status().isOk()).andReturn();
        return new tools.jackson.databind.json.JsonMapper().readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asString();
    }
    @Test void phoneComesOnlyFromWechatAndCannotBelongToTwoUsers() throws Exception {
        String one=bearer(101,1,SessionKind.STUDENT),two=bearer(102,1,SessionKind.STUDENT);
        write("/auth/phone/bind",one,"{\"phoneCode\":\"phone-valid\"}","phone-first").andExpect(status().isOk()).andExpect(jsonPath("$.data.phoneBound").value(true));
        write("/auth/phone/bind",two,"{\"phoneCode\":\"phone-valid\"}","phone-conflict").andExpect(status().isConflict());
        Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_phones WHERE verified=1",Integer.class));
        write("/auth/phone/bind",one,"{\"phoneCode\":\"wrong-app\"}","phone-wrong-app").andExpect(status().isBadRequest());
        write("/auth/phone/bind",one,"{\"phoneCode\":123}","phone-number-code").andExpect(status().isBadRequest());
    }
    @Test void passwordChallengeThenMfaIsOneTimeAndCookieOnlyRefresh() throws Exception {
        jdbc.update("INSERT INTO admin_credentials VALUES(900,'reviewer',?,?, -1,TRUE,CURRENT_TIMESTAMP)",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test-only-password"),crypto.encrypt("admin-mfa","GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"));
        var challenge=write("/admin/auth/login","","{\"username\":\"reviewer\",\"password\":\"test-only-password\"}","admin-password").andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").doesNotExist()).andReturn();
        String id=new tools.jackson.databind.json.JsonMapper().readTree(challenge.getResponse().getContentAsString()).path("data").path("challengeId").asString();
        String body="{\"challengeId\":\""+id+"\",\"code\":\"279037\"}";
        var verified=write("/admin/auth/mfa/verify","",body,"admin-mfa-first").andExpect(status().isOk()).andExpect(jsonPath("$.data.refreshToken").doesNotExist()).andExpect(cookie().secure("ADMIN-REFRESH",true)).andExpect(cookie().httpOnly("ADMIN-REFRESH",true)).andReturn();
        Assertions.assertEquals("Strict",verified.getResponse().getCookie("ADMIN-REFRESH").getAttribute("SameSite"));
        write("/admin/auth/mfa/verify","",body,"admin-mfa-replay").andExpect(status().isUnauthorized());
        var again=write("/admin/auth/login","","{\"username\":\"reviewer\",\"password\":\"test-only-password\"}","admin-password-two").andExpect(status().isOk()).andReturn();
        String nextId=new tools.jackson.databind.json.JsonMapper().readTree(again.getResponse().getContentAsString()).path("data").path("challengeId").asString();
        write("/admin/auth/mfa/verify","","{\"challengeId\":\""+nextId+"\",\"code\":\"279037\"}","admin-totp-replay").andExpect(status().isUnauthorized());
        var csrfBootstrap=mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andReturn();String csrfValue=new tools.jackson.databind.json.JsonMapper().readTree(csrfBootstrap.getResponse().getContentAsString()).path("data").path("token").asString();var csrfCookie=csrfBootstrap.getResponse().getCookie("ADMIN-XSRF-TOKEN");
        var renewed=mvc.perform(post("/api/v1/admin/auth/token/refresh").secure(true).cookie(csrfCookie,verified.getResponse().getCookie("ADMIN-REFRESH")).header("Authorization","Bearer "+"x".repeat(43)).header("X-ADMIN-CSRF",csrfValue).contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.refreshToken").doesNotExist()).andReturn();
        String access=new tools.jackson.databind.json.JsonMapper().readTree(renewed.getResponse().getContentAsString()).path("data").path("accessToken").asString();String bearer="Bearer "+access;
        mvc.perform(get("/api/v1/admin/me").header("Authorization",bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("900"));
        write("/admin/auth/reauth",bearer,"{\"mfaCode\":\"279037\"}","admin-reauth-stale").andExpect(status().isUnauthorized());
        var reauthenticated=write("/admin/auth/reauth",bearer,"{\"mfaCode\":\"637009\"}","admin-reauth-current").andExpect(status().isOk()).andReturn();String reauthToken=new tools.jackson.databind.json.JsonMapper().readTree(reauthenticated.getResponse().getContentAsString()).path("data").path("reauthToken").asString();Assertions.assertTrue(reauthToken.matches("[A-Za-z0-9_-]{43}"));var authService=context.getBean(com.caizhitong.admin.identity.AdminAuthService.class);Actor actor=sessions.authenticate(access,SessionKind.ADMIN).orElseThrow();authService.consumeReauth(reauthToken,actor);Assertions.assertEquals(403,Assertions.assertThrows(com.caizhitong.common.BusinessException.class,()->authService.consumeReauth(reauthToken,actor)).getStatus());
        jdbc.update("UPDATE admin_credentials SET active=FALSE WHERE user_id=900");
        mvc.perform(post("/api/v1/admin/auth/token/refresh").secure(true).cookie(csrfCookie,renewed.getResponse().getCookie("ADMIN-REFRESH")).header("X-ADMIN-CSRF",csrfValue).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE admin_credentials SET active=TRUE WHERE user_id=900");
        write("/admin/auth/logout",bearer,"{}","admin-logout").andExpect(status().isOk()).andExpect(cookie().maxAge("ADMIN-REFRESH",0));
        mvc.perform(get("/api/v1/admin/me").header("Authorization",bearer)).andExpect(status().isUnauthorized());
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency_records",Integer.class));
    }
    String reauth(long user,long campus,long expiry){String token=Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().substring(0,32).getBytes(java.nio.charset.StandardCharsets.UTF_8));secrets.claim("admin-reauth",HexFormat.of().formatHex(crypto.searchHash("admin-reauth",token)),user+":"+campus+":"+expiry,Duration.ofMinutes(5));return token;}
    @Test void roleGrantRequiresOwnCampusPermissionAndOneTimeReauthThenRevocationIsImmediate() throws Exception {
        String admin=bearer(900,1,SessionKind.ADMIN),token=reauth(900,1,2_000_000_300L);
        String body="{\"roleCode\":\"content_moderator\",\"campusId\":\"1\",\"reauthToken\":\""+token+"\",\"reason\":\"分配审核\"}";
        write("/admin/users/101/roles",admin,body,"role-grant-valid").andExpect(status().isOk()).andExpect(jsonPath("$.data.assigned").value(true));
        String newlyAdmin=bearer(101,1,SessionKind.ADMIN);
        write("/admin/users/102/roles",admin,body,"role-grant-replay").andExpect(status().isForbidden());
        var bootstrap=mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andReturn();String csrfValue=new tools.jackson.databind.json.JsonMapper().readTree(bootstrap.getResponse().getContentAsString()).path("data").path("token").asString();
        mvc.perform(delete("/api/v1/admin/users/101/roles/content_moderator?campusId=1").secure(true).cookie(bootstrap.getResponse().getCookie("ADMIN-XSRF-TOKEN")).header("X-ADMIN-CSRF",csrfValue).header("Authorization",admin).header("Idempotency-Key","role-revoke-valid").header("X-Reauth-Token",reauth(900,1,2_000_000_300L)).contentType("application/json").content("{\"reason\":\"职责结束\"}")).andExpect(status().isOk());
        Assertions.assertTrue(sessions.authenticate(newlyAdmin.substring(7),SessionKind.ADMIN).isEmpty());
        Assertions.assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs",Integer.class));
    }
    @Test void wrongOwnerExpiredReauthAndExternalCampusRoleAreDenied() throws Exception {
        String admin=bearer(900,1,SessionKind.ADMIN);
        for(String token:List.of(reauth(900,1,1_999_999_999L),reauth(101,1,2_000_000_300L),reauth(900,2,2_000_000_300L)))write("/admin/users/101/roles",admin,"{\"roleCode\":\"content_moderator\",\"campusId\":\"1\",\"reauthToken\":\""+token+"\",\"reason\":\"分配\"}",UUID.randomUUID().toString()).andExpect(status().isForbidden());
        write("/admin/users/201/roles",admin,"{\"roleCode\":\"content_moderator\",\"campusId\":\"2\",\"reauthToken\":\""+reauth(900,1,2_000_000_300L)+"\",\"reason\":\"分配\"}","role-other-campus").andExpect(status().isForbidden());
        for(String user:List.of("201","999999"))write("/admin/users/"+user+"/roles",admin,"{\"roleCode\":\"content_moderator\",\"campusId\":\"1\",\"reauthToken\":\""+reauth(900,1,2_000_000_300L)+"\",\"reason\":\"分配\"}","role-invisible-"+user).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
    @Test void rosterImportEncryptsValidRowsAndDoesNotGrantStudentCapability() throws Exception {
        String admin=bearer(900,1,SessionKind.ADMIN);
        write("/admin/student-roster/imports",admin,"{\"attachmentId\":\"501\"}","roster-import-valid").andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("pending"));
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM student_roster",Integer.class));
        Assertions.assertTrue(context.containsBean("studentRosterWorker"));context.getBean("studentRosterWorker",Runnable.class).run();
        Assertions.assertEquals("succeeded",jdbc.queryForObject("SELECT status FROM async_jobs",String.class));
        var result=new tools.jackson.databind.json.JsonMapper().readTree(jdbc.queryForObject("SELECT result_json FROM async_jobs",String.class));Assertions.assertEquals(1,result.path("successCount").asInt());Assertions.assertEquals(3,result.path("errorCount").asInt());
        Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM student_roster",Integer.class));
        Assertions.assertNotEquals("20260001",jdbc.queryForObject("SELECT student_id_search FROM student_roster",String.class));
        Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM async_jobs",Integer.class));
        phone(101);String student=bearer(101,1,SessionKind.STUDENT);
        write("/student-verifications",student,"{\"studentNumber\":\"20260001\"}","roster-apply").andExpect(status().isOk()).andExpect(jsonPath("$.data.rosterMatched").value(true)).andExpect(jsonPath("$.data.status").value("pending"));
        Assertions.assertFalse(sessions.authenticate(student.substring(7),SessionKind.STUDENT).orElseThrow().studentVerified());
    }
    @Test void queueKeepsRejectedHistoryDistinctInsteadOfRepeatingCurrentApplication() throws Exception {
        phone(101);String student=bearer(101,1,SessionKind.STUDENT),admin=bearer(900,1,SessionKind.ADMIN);
        write("/student-verifications",student,"{\"studentNumber\":\"20260001\"}","queue-first").andExpect(status().isOk());
        long id=jdbc.queryForObject("SELECT id FROM student_verifications",Long.class);
        write("/admin/student-verifications/"+id+"/decision",admin,"{\"decision\":\"reject\",\"reason\":\"需补资料\",\"version\":0}","queue-reject").andExpect(status().isOk());
        write("/student-verifications",student,"{\"studentNumber\":\"20260002\"}","queue-second").andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/student-verifications?status=rejected").header("Authorization",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(Long.toString(id))).andExpect(jsonPath("$.data.items[0].status").value("rejected"));
    }
    @Test void controlledProvisioningHashesPasswordEncryptsSeedAndAuditsOperator(){
        var provision=Assertions.assertDoesNotThrow(()->context.getBean(com.caizhitong.admin.identity.AdminProvisioning.class));
        provision.initialize(new com.caizhitong.admin.identity.AdminProvisioning.Input(900,102,1,"content_moderator","new-reviewer","controlled-test-password","GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"));
        String hash=jdbc.queryForObject("SELECT password_hash FROM admin_credentials WHERE user_id=102",String.class);Assertions.assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches("controlled-test-password",hash));
        Assertions.assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",crypto.decrypt("admin-mfa",jdbc.queryForObject("SELECT mfa_seed_encrypted FROM admin_credentials WHERE user_id=102",byte[].class)));
        Assertions.assertEquals(900,jdbc.queryForObject("SELECT operator_id FROM admin_audit_logs",Long.class));
        Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency_records",Integer.class));
    }
    @Test void csrfCookieHasStrictSameSiteAndSignedUserCursorCannotChangeScope() throws Exception {
        var bootstrap=mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andReturn();Assertions.assertEquals("Strict",bootstrap.getResponse().getCookie("ADMIN-XSRF-TOKEN").getAttribute("SameSite"));
        String admin=bearer(900,1,SessionKind.ADMIN);var first=mvc.perform(get("/api/v1/admin/users?limit=1").header("Authorization",admin)).andExpect(status().isOk()).andReturn();String cursor=new tools.jackson.databind.json.JsonMapper().readTree(first.getResponse().getContentAsString()).path("data").path("nextCursor").asString();Assertions.assertTrue(cursor.contains("."));
        mvc.perform(get("/api/v1/admin/users").param("limit","1").param("cursor",cursor).header("Authorization",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value("102"));
        mvc.perform(get("/api/v1/admin/users").param("q","other").param("cursor",cursor).header("Authorization",admin)).andExpect(status().isBadRequest());
    }
    @Test void approvalFailureRollsBackVerificationAuditAndIdempotencyTogether() throws Exception {
        phone(101);String student=bearer(101,1,SessionKind.STUDENT),admin=bearer(900,1,SessionKind.ADMIN);write("/student-verifications",student,"{\"studentNumber\":\"20260001\"}","atomic-apply").andExpect(status().isOk());long id=jdbc.queryForObject("SELECT id FROM student_verifications",Long.class);
        jdbc.execute("ALTER TABLE admin_audit_logs ADD CONSTRAINT deny_approve CHECK(action <> 'identity.verified')");
        try{write("/admin/student-verifications/"+id+"/decision",admin,"{\"decision\":\"approve\",\"reason\":\"核验\",\"version\":0}","atomic-approve").andExpect(status().isInternalServerError());Assertions.assertEquals("pending",jdbc.queryForObject("SELECT status FROM student_verifications",String.class));Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs",Integer.class));Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency_records",Integer.class));}finally{jdbc.execute("ALTER TABLE admin_audit_logs DROP CONSTRAINT deny_approve");}
        for(int i=0;i<2;i++)write("/admin/student-verifications/"+id+"/decision",admin,"{\"decision\":\"approve\",\"reason\":\"核验\",\"version\":0}","atomic-approve").andExpect(status().isOk());Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs",Integer.class));
    }
    @Test void pendingDoesNotOccupyNumberButVerifiedOwnershipAndLatestPhoneConsentDo() throws Exception {
        phone(101);phone(102);String one=bearer(101,1,SessionKind.STUDENT),two=bearer(102,1,SessionKind.STUDENT),admin=bearer(900,1,SessionKind.ADMIN);
        write("/student-verifications",one,"{\"studentNumber\":\"20260001\"}","ownership-one").andExpect(status().isOk());write("/student-verifications",two,"{\"studentNumber\":\"20260001\"}","ownership-two").andExpect(status().isOk());long first=jdbc.queryForObject("SELECT id FROM student_verifications WHERE user_id=101",Long.class),second=jdbc.queryForObject("SELECT id FROM student_verifications WHERE user_id=102",Long.class);
        jdbc.update("UPDATE authorizations SET status='revoked',revoked_at=CURRENT_TIMESTAMP WHERE user_id=101");write("/admin/student-verifications/"+first+"/decision",admin,"{\"decision\":\"approve\",\"reason\":\"核验\",\"version\":0}","ownership-revoked").andExpect(status().isForbidden());jdbc.update("UPDATE authorizations SET status='granted',revoked_at=NULL WHERE user_id=101");
        write("/admin/student-verifications/"+first+"/decision",admin,"{\"decision\":\"approve\",\"reason\":\"核验\",\"version\":0}","ownership-approved").andExpect(status().isOk());write("/admin/student-verifications/"+second+"/decision",admin,"{\"decision\":\"approve\",\"reason\":\"核验\",\"version\":0}","ownership-conflict").andExpect(status().isConflict());
    }
    @Test void concurrentFirstProfileUpdateHasOneWinner() throws Exception {
        String token=bearer(101,1,SessionKind.STUDENT);java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);try{List<java.util.concurrent.Callable<Integer>> calls=List.of(()->write("/me/profile/update",token,"{\"skills\":[\"Java\"],\"version\":0}","profile-race-one").andReturn().getResponse().getStatus(),()->write("/me/profile/update",token,"{\"interests\":[\"音乐\"],\"version\":0}","profile-race-two").andReturn().getResponse().getStatus());var statuses=pool.invokeAll(calls).stream().map(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).sorted().toList();Assertions.assertEquals(List.of(200,409),statuses);Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_profiles",Integer.class));}finally{pool.shutdownNow();}
    }
    @Test void rosterFailedReadRetriesOnlyThreeTimesAndExpiredLeaseCanBeReclaimed() throws Exception {
        jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,campus_id,owner_user_id,access_domain,created_at,updated_at,retry_count,max_retries) VALUES(800,'student_roster','{\"attachmentId\":\"500\"}','pending',1,900,'admin',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,3)");Assertions.assertTrue(context.containsBean("studentRosterWorker"));Runnable worker=context.getBean("studentRosterWorker",Runnable.class);
        for(int i=0;i<3;i++){jdbc.update("UPDATE async_jobs SET next_retry_at=NULL WHERE id=800");worker.run();}Assertions.assertEquals("failed",jdbc.queryForObject("SELECT status FROM async_jobs WHERE id=800",String.class));Assertions.assertEquals(3,jdbc.queryForObject("SELECT retry_count FROM async_jobs WHERE id=800",Integer.class));worker.run();Assertions.assertEquals(3,jdbc.queryForObject("SELECT retry_count FROM async_jobs WHERE id=800",Integer.class));
        jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,campus_id,owner_user_id,access_domain,created_at,updated_at,retry_count,max_retries,picked_at,claim_token) VALUES(801,'student_roster','{\"attachmentId\":\"501\"}','running',1,900,'admin',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1,3,TIMESTAMP '2033-05-18 03:20:00','expired')");worker.run();Assertions.assertEquals("succeeded",jdbc.queryForObject("SELECT status FROM async_jobs WHERE id=801",String.class));
    }
    @Test void adminRefreshStoreFailureIsSafe503(){
        SessionStore store=org.mockito.Mockito.mock(SessionStore.class);org.mockito.Mockito.when(store.find(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(SessionKind.ADMIN))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("internal-redis-secret"));
        var service=new com.caizhitong.admin.identity.AdminAuthService(jdbc,crypto,context.getBean(IdentityService.class),secrets,context.getBean(com.caizhitong.common.ratelimit.RateLimiter.class),context.getBean(Clock.class),context.getBean(ActorLoader.class),context.getBean(CurrentActor.class),sessions,store,context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var failure=Assertions.assertThrows(com.caizhitong.common.BusinessException.class,()->service.refresh("z".repeat(43)));Assertions.assertEquals(503,failure.getStatus());Assertions.assertFalse(failure.getMessage().contains("secret"));Assertions.assertNull(failure.getCause());
    }
    @Test void profileNameUpdateReplaysVersionButNeverCachesAvatarSignature()throws Exception{
        String avatar="/legacy-avatar?sig=test-only-signature";jdbc.update("UPDATE users SET avatar_url=? WHERE id=101",avatar);String bearer=bearer(101,1,SessionKind.STUDENT);
        for(int i=0;i<2;i++)write("/me/update",bearer,"{\"nickname\":\"测试昵称\",\"version\":0}","avatar-cache-check").andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.avatarUrl").value(avatar));
        var record=jdbc.queryForMap("SELECT scope_digest,key_digest,request_digest,response_encrypted FROM api_idempotency_records");String purpose="idempotency-result:"+record.get("scope_digest")+":"+record.get("key_digest")+":"+record.get("request_digest");String cached=crypto.decrypt(purpose,(byte[])record.get("response_encrypted"));Assertions.assertFalse(cached.contains("test-only-signature"));
        Assertions.assertEquals(1,jdbc.queryForObject("SELECT version FROM users WHERE id=101",Integer.class));
    }
    @Test void adminUserListReportsRealCurrentPhoneAuthorization()throws Exception{phone(102);jdbc.update("UPDATE users SET nickname='已绑定' WHERE id=102");mvc.perform(get("/api/v1/admin/users?q=已绑定").header("Authorization",bearer(900,1,SessionKind.ADMIN))).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].phoneBound").value(true));}
    @Test void meReadDoesNotHoldAConnectionWhileIssuingNestedQueries(){
        try(var single=new com.zaxxer.hikari.HikariDataSource()){single.setJdbcUrl("jdbc:h2:mem:identity_http;MODE=MySQL;DB_CLOSE_DELAY=-1");single.setUsername("sa");single.setPassword("");single.setMaximumPoolSize(1);single.setConnectionTimeout(250);var service=new IdentityService(new JdbcTemplate(single),context.getBean(CurrentActor.class),crypto,context.getBean(com.caizhitong.common.SnowflakeIds.class),context.getBean(tools.jackson.databind.json.JsonMapper.class),context.getBean(Clock.class),context.getBean(com.caizhitong.common.pagination.CursorCodec.class),context.getBean(com.caizhitong.material.files.AvatarUrls.class));Assertions.assertDoesNotThrow(()->service.user(new Actor(101,1,Set.of(),false,false,"active",SessionKind.STUDENT)));}
    }
    @TestConfiguration static class Fixtures {
        @Bean @Primary IdentityFiles testIdentityFiles(){return new IdentityFiles(){public void validateAvatar(Actor a,long id){throw new com.caizhitong.common.BusinessException(503,"IDENTITY_FILES_UNAVAILABLE","未接入");}public void validateRoster(Actor a,long id){Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());if(id!=501)throw new com.caizhitong.common.BusinessException(503,"IDENTITY_FILES_UNAVAILABLE","未接入");}public byte[] roster(Actor a,long id,int max){Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());validateRoster(a,id);return "studentNumber,college,grade,status\n20260001,计算机学院,2026,active\n20260001,计算机学院,2026,active\n20260002,其他学院,2026,active\n20260003,计算机学院,2026,broken\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);}};}
        @Bean @Primary Clock identityClock(){return Clock.fixed(Instant.ofEpochSecond(2_000_000_000L),ZoneOffset.UTC);}
        @Bean @Primary com.caizhitong.common.ratelimit.RateCounter memoryRateCounter(){return (bucket,window)->1L;}
        @Bean @Primary OneTimeSecrets memorySecrets(){return new OneTimeSecrets(){final Map<String,String> data=new HashMap<>();public synchronized boolean claim(String n,String d,String v,Duration ttl){return data.putIfAbsent(n+":"+d,v)==null;}public synchronized String consume(String n,String d){return data.remove(n+":"+d);}};}
        @Bean @Primary com.caizhitong.integration.wechat.WechatGateway testWechat(){return new com.caizhitong.integration.wechat.WechatGateway(){public Identity login(String code){String openid=code.substring(5,code.lastIndexOf('-'));return new Identity("test-app",openid,"shared-union");}public Phone phone(String code){return new Phone(code.equals("wrong-app")?"other-app":"test-app","86","13800000000");}};}
        @Bean @Primary SessionStore memorySessionStore() { return new SessionStore() {
            final Map<String,SessionRecord> records=new HashMap<>();
            public synchronized void create(SessionRecord r){records.put(r.accessDigest(),r);records.put(r.refreshDigest(),r);}
            public synchronized Optional<SessionRecord> find(String digest,SessionKind kind){return Optional.ofNullable(records.get(digest)).filter(r->r.kind()==kind);}
            public synchronized boolean rotate(SessionRecord old,SessionRecord next){if(!records.containsKey(old.refreshDigest()))return false;records.remove(old.accessDigest());records.remove(old.refreshDigest());create(next);return true;}
            public synchronized boolean revoke(SessionRecord r){if(!records.containsKey(r.refreshDigest()))return false;records.remove(r.accessDigest());records.remove(r.refreshDigest());return true;}
        }; }
    }
}
