package com.caizhitong.integration.storage;

import com.caizhitong.common.SensitiveDataCrypto;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class LegacyFileKeyConverterTest {
    @Test void explicitBackupAndDedicatedPermissionAreRequiredAndConversionCanBeRestored(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:legacy_keys;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE material_versions(id BIGINT PRIMARY KEY,object_key VARCHAR(512),object_key_encrypted VARBINARY(2048))");
        jdbc.execute("CREATE TABLE file_key_migrations(table_name VARCHAR(32),row_id BIGINT,backup_reference VARCHAR(128),original_key_encrypted VARBINARY(2048),converted_at TIMESTAMP,restored_at TIMESTAMP,PRIMARY KEY(table_name,row_id))");
        jdbc.update("INSERT INTO material_versions(id,object_key) VALUES(1,'legacy/private/proof.pdf')");
        byte[] hmac=new byte[32];hmac[0]=1;var crypto=new SensitiveDataCrypto(new byte[32],hmac);
        var denied=new LegacyFileKeyConverter(source,crypto,(connection,table)->{throw new SecurityException("Dedicated operator required");});
        assertThrows(SecurityException.class,()->denied.convert("material_versions",1,"backup-verified"));
        var tool=new LegacyFileKeyConverter(source,crypto,(connection,table)->{});
        assertThrows(IllegalArgumentException.class,()->tool.convert("material_versions",1,""));
        assertEquals("legacy/private/proof.pdf",jdbc.queryForObject("SELECT object_key FROM material_versions",String.class));
        tool.convert("material_versions",1,"backup-verified");
        assertNull(jdbc.queryForObject("SELECT object_key FROM material_versions",String.class));
        assertEquals("legacy/private/proof.pdf",crypto.decrypt("file-object-key",jdbc.queryForObject("SELECT object_key_encrypted FROM material_versions",byte[].class)));
        tool.restore("material_versions",1,"backup-verified");
        assertEquals("legacy/private/proof.pdf",jdbc.queryForObject("SELECT object_key FROM material_versions",String.class));
        assertNull(jdbc.queryForObject("SELECT object_key_encrypted FROM material_versions",byte[].class));
    }
    @Test void failureRollsBackCiphertextAndKeepsPlaintext(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:legacy_failure;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE material_versions(id BIGINT PRIMARY KEY,object_key VARCHAR(512),object_key_encrypted VARBINARY(2048),CHECK(object_key IS NOT NULL))");
        jdbc.execute("CREATE TABLE file_key_migrations(table_name VARCHAR(32),row_id BIGINT,backup_reference VARCHAR(128),original_key_encrypted VARBINARY(2048),converted_at TIMESTAMP,restored_at TIMESTAMP,PRIMARY KEY(table_name,row_id))");
        jdbc.update("INSERT INTO material_versions(id,object_key) VALUES(1,'legacy/private/proof.pdf')");byte[] hmac=new byte[32];hmac[0]=1;
        var tool=new LegacyFileKeyConverter(source,new SensitiveDataCrypto(new byte[32],hmac),(connection,table)->{});
        assertThrows(RuntimeException.class,()->tool.convert("material_versions",1,"backup-verified"));
        assertEquals("legacy/private/proof.pdf",jdbc.queryForObject("SELECT object_key FROM material_versions",String.class));assertNull(jdbc.queryForObject("SELECT object_key_encrypted FROM material_versions",byte[].class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM file_key_migrations",Integer.class));
    }
}
