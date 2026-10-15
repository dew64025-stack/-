package com.caizhitong.security;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class DatabaseActorLoaderTest {
    @Autowired DatabaseActorLoader loader;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void database() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS campuses (id BIGINT PRIMARY KEY,status VARCHAR(32),deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS users (id BIGINT PRIMARY KEY,campus_id BIGINT,status VARCHAR(32),deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_phones (id BIGINT PRIMARY KEY,user_id BIGINT,verified INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS student_verifications (id BIGINT PRIMARY KEY,user_id BIGINT,campus_id BIGINT,status VARCHAR(32))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS roles (id BIGINT PRIMARY KEY,code VARCHAR(64))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_roles (id BIGINT PRIMARY KEY,user_id BIGINT,role_id BIGINT,campus_id BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS authorizations (id BIGINT PRIMARY KEY,user_id BIGINT,type VARCHAR(32),status VARCHAR(32),created_at TIMESTAMP,revoked_at TIMESTAMP)");
        for (String table : new String[]{"authorizations", "user_roles", "roles", "student_verifications", "user_phones", "users", "campuses"}) jdbc.execute("DELETE FROM " + table);
        jdbc.update("INSERT INTO campuses VALUES (1,'active',NULL),(2,'active',NULL)");
        jdbc.update("INSERT INTO users VALUES (10001,1,'active',NULL)");
        jdbc.update("INSERT INTO user_phones VALUES (1,10001,1)");
        jdbc.update("INSERT INTO student_verifications VALUES (1,10001,1,'verified')");
        jdbc.update("INSERT INTO roles VALUES (1,'student'),(2,'content_moderator'),(3,'campus_admin'),(4,'security_investigator')");
        jdbc.update("INSERT INTO user_roles VALUES (1,10001,1,1)");
    }
    @Test void verifiedPhoneWithoutCurrentConsentIsNotStudentPrivilege() {
        Actor actor = loader.load(10001,1,SessionKind.STUDENT).orElseThrow();
        assertTrue(actor.studentVerified()); assertFalse(actor.phoneBound());
        jdbc.update("INSERT INTO authorizations VALUES (1,10001,'phone','granted','2026-10-13 00:00:00',NULL)");
        assertTrue(loader.load(10001,1,SessionKind.STUDENT).orElseThrow().phoneBound());
        jdbc.update("INSERT INTO authorizations VALUES (2,10001,'phone','revoked','2026-10-13 01:00:00','2026-10-13 01:00:00')");
        assertFalse(loader.load(10001,1,SessionKind.STUDENT).orElseThrow().phoneBound());
    }
    @Test void adminRoleMustBeServerAssignedForThisCampus() {
        assertTrue(loader.load(10001,1,SessionKind.ADMIN).isEmpty());
        jdbc.update("INSERT INTO user_roles VALUES (2,10001,2,2)");
        assertTrue(loader.load(10001,1,SessionKind.ADMIN).isEmpty());
        jdbc.update("UPDATE user_roles SET campus_id=1 WHERE id=2");
        assertTrue(loader.load(10001,1,SessionKind.ADMIN).orElseThrow().permissions().contains("content.review"));
        assertTrue(loader.load(10001,2,SessionKind.ADMIN).isEmpty());
        assertTrue(loader.load(10001,1,SessionKind.STUDENT).orElseThrow().permissions().isEmpty());
        jdbc.update("DELETE FROM user_roles WHERE id=2");
        assertTrue(loader.load(10001,1,SessionKind.ADMIN).isEmpty());
    }
    @Test void disabledIdentityIsRetainedButSoftDeletedUserAndClosedCampusAreNot() {
        jdbc.update("UPDATE users SET status='banned' WHERE id=10001");
        assertEquals("banned", loader.load(10001,1,SessionKind.STUDENT).orElseThrow().status());
        jdbc.update("UPDATE users SET deleted_at=CURRENT_TIMESTAMP WHERE id=10001");
        assertTrue(loader.load(10001,1,SessionKind.STUDENT).isEmpty());
        jdbc.update("UPDATE users SET deleted_at=NULL WHERE id=10001");
        jdbc.update("UPDATE campuses SET status='closed' WHERE id=1");
        assertTrue(loader.load(10001,1,SessionKind.STUDENT).isEmpty());
    }
}
