package com.caizhitong.moderation;

import com.caizhitong.common.*;
import com.caizhitong.security.Actor;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Owns private connection/transaction objects: never registered as a business DataSource or manager. */
@Component("investigationGateway")
public class InvestigationGateway {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SensitiveDataCrypto crypto;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    public InvestigationGateway(@Value("${app.security.investigation.url:}")String url,
            @Value("${app.security.investigation.username:}")String username,
            @Value("${app.security.investigation.password:}")String password,
            @Value("${spring.datasource.username:}")String businessUsername,
            SensitiveDataCrypto crypto,SnowflakeIds ids,Clock clock,JsonMapper mapper){
        this.crypto=crypto;this.ids=ids;this.clock=clock;this.mapper=mapper;
        if(url.isBlank()||username.isBlank()||password.isBlank()||username.equals(businessUsername)){
            jdbc=null;transaction=null;
        }else{
            var source=new DriverManagerDataSource(url,username,password);
            jdbc=new JdbcTemplate(source);
            transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
            transaction.setTimeout(10);
        }
    }
    public void requireAvailable(){if(jdbc==null)throw unavailable();}
    public GovernanceViews.Investigation disclose(Actor actor,String investigationId,String type,long target,Long report,String reason){
        requireAvailable();
        try {
            return transaction.execute(status->{
                String sql=switch(type){
                    case "community_post"->"SELECT i.user_id_encrypted FROM pseudo_identities i JOIN community_posts p ON p.id=i.post_id WHERE p.campus_id=? AND p.id=? FOR SHARE";
                    case "community_comment"->"SELECT i.user_id_encrypted FROM pseudo_identities i JOIN community_comments c ON c.id=i.comment_id JOIN community_posts p ON p.id=c.post_id WHERE p.campus_id=? AND c.id=? FOR SHARE";
                    default->throw new BusinessException(400,"VALIDATION_FAILED","请检查填写的信息");
                };
                var matches=jdbc.queryForList(sql,byte[].class,actor.campusId(),target);
                if(matches.size()!=1)throw new BusinessException(404,"NOT_FOUND","未找到请求的资源");
                long user=Long.parseLong(crypto.decrypt("anonymous-identity",matches.getFirst()));
                Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("investigationId",investigationId);receipt.put("campusId",Long.toString(actor.campusId()));if(report!=null)receipt.put("reportId",Long.toString(report));
                jdbc.update("INSERT INTO security_investigations(id,investigator_id,reason,target_user_id,target_type,target_id,result_json,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    ids.nextId(),actor.userId(),reason,user,type,target,mapper.writeValueAsString(receipt),LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
                return new GovernanceViews.Investigation(investigationId,new GovernanceViews.Subject(Long.toString(user)),true);
            });
        }catch(DataAccessException|TransactionException|IllegalArgumentException error){throw unavailable();}
    }
    private BusinessException unavailable(){return new BusinessException(503,"INVESTIGATION_UNAVAILABLE","安全调查暂时不可用",null,true);}
}
