package com.caizhitong.integration.storage;

import com.caizhitong.common.SensitiveDataCrypto;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit operator CLI only: never a Spring bean and never executed during application startup. */
public final class LegacyFileKeyConverter {
    @FunctionalInterface interface PermissionCheck { void authorize(Connection connection,String table)throws SQLException; }
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final SensitiveDataCrypto crypto;
    private final PermissionCheck permissions;
    private final TransactionTemplate tx;
    public LegacyFileKeyConverter(DataSource source,SensitiveDataCrypto crypto){this(source,crypto,LegacyFileKeyConverter::operator);}
    LegacyFileKeyConverter(DataSource source,SensitiveDataCrypto crypto,PermissionCheck permissions){this.source=source;this.jdbc=new JdbcTemplate(source);this.crypto=crypto;this.permissions=permissions;this.tx=new TransactionTemplate(new DataSourceTransactionManager(source));this.tx.setTimeout(10);}
    public void convert(String table,long id,String backupReference){
        validate(table,id,backupReference);
        tx.executeWithoutResult(status->{
            authorize(table);
            var row=jdbc.queryForMap("SELECT object_key,object_key_encrypted FROM "+table+" WHERE id=? FOR UPDATE",id);
            String original=(String)row.get("object_key");
            if(original==null)throw new IllegalStateException("Row has no legacy plaintext; inspect migration journal");
            if(row.get("object_key_encrypted")!=null)throw new IllegalStateException("Mixed legacy state requires operator inspection");
            byte[] encrypted=crypto.encrypt("file-object-key",original);
            jdbc.update("UPDATE "+table+" SET object_key_encrypted=? WHERE id=? AND object_key_encrypted IS NULL",encrypted,id);
            byte[] stored=jdbc.queryForObject("SELECT object_key_encrypted FROM "+table+" WHERE id=?",byte[].class,id);
            if(!original.equals(crypto.decrypt("file-object-key",stored)))throw new IllegalStateException("Encrypted read-back failed");
            jdbc.update("INSERT INTO file_key_migrations(table_name,row_id,backup_reference,original_key_encrypted,converted_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",table,id,backupReference,stored);
            if(jdbc.update("UPDATE "+table+" SET object_key=NULL WHERE id=? AND object_key=?",id,original)!=1)throw new IllegalStateException("Legacy row changed");
        });
    }
    public void restore(String table,long id,String backupReference){
        validate(table,id,backupReference);
        tx.executeWithoutResult(status->{
            authorize(table);
            var row=jdbc.queryForMap("SELECT object_key,object_key_encrypted FROM "+table+" WHERE id=? FOR UPDATE",id);
            var journal=jdbc.queryForMap("SELECT backup_reference,original_key_encrypted,restored_at FROM file_key_migrations WHERE table_name=? AND row_id=? FOR UPDATE",table,id);
            if(!backupReference.equals(journal.get("backup_reference"))||journal.get("restored_at")!=null||row.get("object_key")!=null||!Arrays.equals((byte[])row.get("object_key_encrypted"),(byte[])journal.get("original_key_encrypted")))throw new IllegalStateException("Restore verification failed");
            String original=crypto.decrypt("file-object-key",(byte[])journal.get("original_key_encrypted"));
            jdbc.update("UPDATE "+table+" SET object_key=?,object_key_encrypted=NULL WHERE id=?",original,id);
            jdbc.update("UPDATE file_key_migrations SET restored_at=CURRENT_TIMESTAMP WHERE table_name=? AND row_id=?",table,id);
        });
    }
    private void authorize(String table){
        Connection connection=DataSourceUtils.getConnection(source);
        try{permissions.authorize(connection,table);}catch(SQLException e){throw new SecurityException("Dedicated conversion permissions are required");}
        finally{DataSourceUtils.releaseConnection(connection,source);}
    }
    private static void validate(String table,long id,String backup){
        if(!Set.of("material_files","material_versions").contains(table)||id<1||backup==null||!backup.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{7,127}"))throw new IllegalArgumentException("Explicit table, row and verified backup reference are required");
    }
    private static void operator(Connection connection,String table)throws SQLException {
        String user;
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT CURRENT_USER()")){r.next();user=r.getString(1);}
        if(!user.startsWith("czt_file_key_operator@"))throw new SecurityException("Dedicated conversion account required");
        String grantee="'"+user.replace("@","'@'")+"'";
        var required=new HashSet<>(Set.of("object_key","object_key_encrypted"));
        try(var s=connection.prepareStatement("SELECT COLUMN_NAME FROM information_schema.COLUMN_PRIVILEGES WHERE GRANTEE=? AND TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND PRIVILEGE_TYPE='UPDATE'")){
            s.setString(1,grantee);s.setString(2,table);try(var r=s.executeQuery()){while(r.next())required.remove(r.getString(1));}
        }
        if(!required.isEmpty())throw new SecurityException("Explicit conversion column grants required");
        try(var s=connection.prepareStatement("SELECT PRIVILEGE_TYPE FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE=? AND TABLE_SCHEMA=DATABASE() AND TABLE_NAME='file_key_migrations'")){
            s.setString(1,grantee);var grants=new HashSet<String>();try(var r=s.executeQuery()){while(r.next())grants.add(r.getString(1));}if(!grants.containsAll(Set.of("SELECT","INSERT","UPDATE")))throw new SecurityException("Migration journal grants required");
        }
    }
    public static void main(String[] args){
        try{run(args);}catch(RuntimeException e){System.err.println("Conversion refused or failed; inspect the controlled database journal and backup.");System.exit(1);}
    }
    private static void run(String[] args){
        if(args.length!=5||!args[0].equals("--backup-confirmed")||!Set.of("convert","restore").contains(args[4]))throw new IllegalArgumentException("--backup-confirmed <reference> <table> <rowId> <convert|restore>");
        String url=required("MYSQL_URL"),user=required("LEGACY_FILES_DB_USER");
        if(!user.equals("czt_file_key_operator"))throw new SecurityException("Dedicated conversion account required");
        var source=new DriverManagerDataSource(url,user,required("LEGACY_FILES_DB_PASSWORD"));
        var crypto=new SensitiveDataCrypto(Base64.getDecoder().decode(required("APP_ENCRYPTION_KEY")),Base64.getDecoder().decode(required("APP_HMAC_KEY")));
        var converter=new LegacyFileKeyConverter(source,crypto);long id=Long.parseLong(args[3]);
        if(args[4].equals("convert"))converter.convert(args[2],id,args[1]);else converter.restore(args[2],id,args[1]);
        System.out.println("Verified "+args[4]+" for "+args[2]+" row "+id);
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing required operator configuration");return value;}
}
