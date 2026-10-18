package com.caizhitong.messaging;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Existing synthetic V22 chats must survive V25/V27 without leaking their old shared history. */
@EnabledIfEnvironmentVariable(named="CZT_ABC_C_MYSQL_TEST",matches="true")
class MysqlDMigrationTest {
    private record Database(String url,String user,String password) {
        Flyway flyway(String target) {
            return Flyway.configure().dataSource(url,user,password)
                    .locations("filesystem:"+Path.of("..","sql").toAbsolutePath())
                    .target(target).cleanDisabled(true).load();
        }
    }

    private Database legacy(String suffix,boolean mixed) throws Exception {
        String port=System.getenv("CZT_ABC_C_VERIFIED_PORT"),schema=System.getenv("CZT_ABC_C_SCHEMA");
        String rootPassword=System.getenv("CZT_ABC_C_ROOT_PASSWORD");
        if(port==null || !port.matches("[0-9]{1,5}") || Set.of("3306","13306").contains(port)
                || schema==null || !schema.matches("czt_abc_c_[a-z0-9_]+")
                || !"abc-review-repairs-20261015".equals(System.getenv("CZT_ABC_C_VERIFIED_LABEL")))
            throw new IllegalStateException("Owned loopback test container required");
        schema+="_"+suffix;
        String options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        String server="jdbc:mysql://127.0.0.1:"+port+"/";
        String account="czt_d_m_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        String password=UUID.randomUUID().toString();
        try(var connection=DriverManager.getConnection(server+options,"root",rootPassword);var s=connection.createStatement()) {
            s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            s.execute("CREATE USER '"+account+"'@'%' IDENTIFIED BY '"+password+"'");
            s.execute("GRANT CREATE,ALTER,DROP,INDEX,REFERENCES,CREATE TEMPORARY TABLES,CREATE ROUTINE,ALTER ROUTINE,EXECUTE,SELECT,INSERT,UPDATE,DELETE ON "+schema+".* TO '"+account+"'@'%'");
        } finally {rootPassword=null;}
        var database=new Database(server+schema+options,account,password);
        database.flyway("22").migrate();
        try(var connection=DriverManager.getConnection(database.url(),account,password);var s=connection.createStatement()) {
            s.execute("INSERT INTO campuses(id,name,code,created_at,updated_at) VALUES(1,'Synthetic','d-migration',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
            s.execute("INSERT INTO users(id,campus_id,status,created_at,updated_at) VALUES(101,1,'active',UTC_TIMESTAMP(),UTC_TIMESTAMP()),(102,1,'active',UTC_TIMESTAMP(),UTC_TIMESTAMP()),(103,1,'active',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
            s.execute("INSERT INTO mutual_aid_posts(id,campus_id,user_id,category,title,created_at,updated_at) VALUES(501,1,101,'secondhand','Synthetic',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
            s.execute("INSERT INTO conversations(id,campus_id,type,title,context_type,context_id,created_by,created_at,updated_at) VALUES(601,1,'direct','Synthetic','mutual_aid_post',501,102,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
            s.execute("INSERT INTO conversation_members(id,conversation_id,user_id,created_at) VALUES(701,601,101,UTC_TIMESTAMP()),(702,601,102,UTC_TIMESTAMP())");
            s.execute("INSERT INTO messages(id,conversation_id,sender_id,content,created_at) VALUES(801,601,102,'Original message',UTC_TIMESTAMP())");
            if(mixed)s.execute("INSERT INTO conversation_members(id,conversation_id,user_id,created_at) VALUES(703,601,103,UTC_TIMESTAMP())");
        }
        database.flyway("26").migrate();
        return database;
    }

    @Test void upgradePreservesLegitimatePrivateHistoryAndSeparateBuyerContexts() throws Exception {
        var database=legacy("safe",false);
        database.flyway("27").migrate();
        try(var connection=DriverManager.getConnection(database.url(),database.user(),database.password());var s=connection.createStatement()) {
            try(var rows=s.executeQuery("SELECT participant_key,access_revoked_at FROM conversations WHERE id=601")) {
                assertTrue(rows.next());assertEquals("101:102",rows.getString(1));assertNull(rows.getTimestamp(2));
            }
            try(var rows=s.executeQuery("SELECT content FROM messages WHERE id=801")){assertTrue(rows.next());assertEquals("Original message",rows.getString(1));}
            s.execute("INSERT INTO conversations(id,campus_id,type,title,context_type,context_id,participant_key,created_by,created_at,updated_at) VALUES(602,1,'direct','Separate','mutual_aid_post',501,'101:103',103,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        }
    }

    @Test void upgradeQuarantinesMixedPrivateChatWithoutDiscardingHistory() throws Exception {
        var database=legacy("mixed",true);
        database.flyway("27").migrate();
        try(var connection=DriverManager.getConnection(database.url(),database.user(),database.password());var s=connection.createStatement()) {
            try(var rows=s.executeQuery("SELECT access_revoked_at FROM conversations WHERE id=601")){assertTrue(rows.next());assertNotNull(rows.getTimestamp(1));}
            try(var rows=s.executeQuery("SELECT COUNT(*) FROM conversation_members WHERE conversation_id=601")){assertTrue(rows.next());assertEquals(3,rows.getInt(1));}
            try(var rows=s.executeQuery("SELECT content FROM messages WHERE id=801")){assertTrue(rows.next());assertEquals("Original message",rows.getString(1));}
        }
    }
}
