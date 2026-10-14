package com.caizhitong.identity;
import com.caizhitong.campus.CampusService;
import com.caizhitong.common.*;
import com.caizhitong.common.ratelimit.RateLimiter;
import com.caizhitong.integration.wechat.WechatGateway;
import com.caizhitong.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.caizhitong.identity.IdentityInputs.*;

@Service public class WechatIdentityService {
    public record LoginResult(String accessToken,long expiresIn,String refreshToken,long refreshExpiresIn,IdentityViews.User user){}
    private record Account(long user,long campus){}
    private final JdbcTemplate jdbc;private final WechatGateway wechat;private final IdentityService identity;private final CampusService campuses;private final SessionTokenService sessions;private final CurrentActor current;private final SnowflakeIds ids;private final SensitiveDataCrypto crypto;private final OneTimeSecrets secrets;private final RateLimiter rates;private final TransactionTemplate tx;private final String appId;
    public WechatIdentityService(JdbcTemplate jdbc,WechatGateway wechat,IdentityService identity,CampusService campuses,SessionTokenService sessions,CurrentActor current,SnowflakeIds ids,SensitiveDataCrypto crypto,OneTimeSecrets secrets,RateLimiter rates,PlatformTransactionManager manager,@Value("${app.wechat.app-id:}")String appId){this.jdbc=jdbc;this.wechat=wechat;this.identity=identity;this.campuses=campuses;this.sessions=sessions;this.current=current;this.ids=ids;this.crypto=crypto;this.secrets=secrets;this.rates=rates;this.tx=new TransactionTemplate(manager);this.tx.setTimeout(10);this.appId=appId;}
    public LoginResult login(Login input,String ip){String code=text(input.code(),256,false);long campus=campuses.open(text(input.campusCode(),64,false));rates.check("wx-login:"+identity.hash("login-ip",ip),20,Duration.ofMinutes(1));
        if(!secrets.claim("wechat-code",identity.hash("wechat-code",code),"used",Duration.ofMinutes(5)))throw IdentityService.conflict();
        var remote=wechat.login(code);if(appId.isBlank()||!appId.equals(remote.appId())||remote.openid()==null||remote.openid().isBlank()||remote.openid().length()>128)throw new BusinessException(502,"WECHAT_FAILED","微信身份校验失败");
        Account account;
        try{account=tx.execute(status->{Account existing=find(remote);if(existing!=null)return existing;long user=ids.nextId();var now=identity.now();jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(?,?,'active',0,?,?)",user,campus,now,now);jdbc.update("INSERT INTO user_wechat_identities(id,user_id,app_id,openid,unionid,created_at) VALUES(?,?,?,?,?,?)",ids.nextId(),user,appId,remote.openid(),remote.unionid(),now);return new Account(user,campus);});}
        catch(DuplicateKeyException e){account=find(remote);if(account==null)throw IdentityService.conflict();}
        var tokens=sessions.issue(account.user(),account.campus(),SessionKind.STUDENT);Actor actor=sessions.authenticate(tokens.accessToken(),SessionKind.STUDENT).orElseThrow(IdentityService::forbidden);
        return new LoginResult(tokens.accessToken(),tokens.expiresIn(),tokens.refreshToken(),tokens.refreshExpiresIn(),identity.user(actor));}
    private Account find(WechatGateway.Identity remote){var rows=jdbc.query("SELECT u.id,u.campus_id FROM user_wechat_identities w JOIN users u ON u.id=w.user_id WHERE w.app_id=? AND w.openid=? AND u.deleted_at IS NULL",(r,i)->new Account(r.getLong(1),r.getLong(2)),appId,remote.openid());return rows.isEmpty()?null:rows.getFirst();}
    public IdentityViews.Bound bind(Phone input){Actor actor=current.require();rates.check("wx-phone:"+actor.userId(),10,Duration.ofMinutes(1));var remote=wechat.phone(text(input.phoneCode(),256,false));
        if(appId.isBlank()||!appId.equals(remote.appId())||remote.countryCode()==null||!remote.countryCode().matches("[1-9][0-9]{0,2}")||remote.nationalNumber()==null||!remote.nationalNumber().matches("[0-9]{4,14}")||remote.countryCode().length()+remote.nationalNumber().length()>15)throw invalid();
        String number="+"+remote.countryCode()+remote.nationalNumber(),hash=identity.hash("phone",number);
        try{return tx.execute(status->{jdbc.queryForList("SELECT id FROM users WHERE id=? AND campus_id=? FOR UPDATE",actor.userId(),actor.campusId());var owners=jdbc.queryForList("SELECT user_id FROM user_phones WHERE phone_search=?",Long.class,hash);if(!owners.isEmpty()&&owners.getFirst()!=actor.userId())throw IdentityService.conflict();
            jdbc.update("UPDATE user_phones SET verified=0 WHERE user_id=? AND verified=1",actor.userId());if(owners.isEmpty())jdbc.update("INSERT INTO user_phones(id,user_id,phone_encrypted,phone_search,verified,created_at) VALUES(?,?,?,?,1,?)",ids.nextId(),actor.userId(),crypto.encrypt("phone",number),hash,identity.now());else jdbc.update("UPDATE user_phones SET verified=1 WHERE user_id=? AND phone_search=?",actor.userId(),hash);
            jdbc.update("UPDATE authorizations SET status='revoked',revoked_at=?,version=version+1 WHERE user_id=? AND type='phone' AND status='granted' AND revoked_at IS NULL",identity.now(),actor.userId());jdbc.update("INSERT INTO authorizations(id,user_id,type,scope,status,version,granted_at,created_at) VALUES(?,?,'phone','wechat-phone','granted',0,?,?)",ids.nextId(),actor.userId(),identity.now(),identity.now());return new IdentityViews.Bound(true,IdentityService.mask(number));});}catch(DuplicateKeyException e){throw IdentityService.conflict();}}
}
