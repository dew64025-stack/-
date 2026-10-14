package com.caizhitong.admin.identity;

import com.caizhitong.common.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Invoked only by the controlled CLI; there is deliberately no HTTP registration route. */
@Service public class AdminBootstrapService implements AdminProvisioning {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final SnowflakeIds ids;private final SensitiveDataCrypto crypto;private final Clock clock;
    public AdminBootstrapService(JdbcTemplate jdbc,PlatformTransactionManager manager,SnowflakeIds ids,SensitiveDataCrypto crypto,Clock clock){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.tx.setTimeout(10);this.ids=ids;this.crypto=crypto;this.clock=clock;}
    public void initialize(Input input){if(input==null||input.operatorId()<1||input.userId()<1||input.campusId()<1||!Set.of("content_moderator","business_operator","resource_admin","campus_admin","platform_admin","security_investigator").contains(input.roleCode())||input.username()==null||input.username().isBlank()||input.username().length()>64||input.password()==null||input.password().length()<12||input.password().getBytes(StandardCharsets.UTF_8).length>72)throw new IllegalArgumentException("Invalid explicit administrator input");Totp.decode(input.mfaSeed());String hash=new BCryptPasswordEncoder(12).encode(input.password());byte[] seed=crypto.encrypt("admin-mfa",input.mfaSeed());
        tx.executeWithoutResult(status->{var users=jdbc.queryForList("SELECT id FROM users WHERE id IN (?,?) AND campus_id=? AND status='active' AND deleted_at IS NULL FOR UPDATE",Long.class,input.userId(),input.operatorId(),input.campusId());if(!users.contains(input.userId())||!users.contains(input.operatorId()))throw new IllegalArgumentException("Administrator and operator must belong to the explicit campus");long role=jdbc.queryForObject("SELECT id FROM roles WHERE code=?",Long.class,input.roleCode());LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);jdbc.update("INSERT INTO admin_credentials(user_id,username,password_hash,mfa_seed_encrypted,last_totp_step,active,created_at) VALUES(?,?,?,?,-1,TRUE,?)",input.userId(),input.username(),hash,seed,now);long scope=Set.of("platform_admin","security_investigator").contains(input.roleCode())?0:input.campusId();jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id,created_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",ids.nextId(),input.userId(),role,scope,now);jdbc.update("INSERT INTO admin_audit_logs(id,operator_id,action,target_type,target_id,reason,detail_json,created_at) VALUES(?,?,'admin.bootstrap','admin',?,NULL,'{}',?)",ids.nextId(),input.operatorId(),input.userId(),now);});}
}
