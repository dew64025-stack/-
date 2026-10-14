package com.caizhitong.admin.identity;

import com.caizhitong.common.*;
import com.caizhitong.common.ratelimit.RateLimiter;
import com.caizhitong.identity.*;
import com.caizhitong.identity.IdentityInputs.*;
import com.caizhitong.security.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.caizhitong.identity.IdentityInputs.*;

@Service public class AdminAuthService {
    public record Challenge(String challengeId,boolean mfaRequired,Instant expiresAt){}
    public record Admin(String id,String nickname,Set<String> roles,List<String> campusScopes,Set<String> permissions){}
    public record Result(String accessToken,long expiresIn,String refreshToken,long refreshExpiresIn,Admin admin,Set<String> permissions){}
    public record ReauthResult(String reauthToken,Instant expiresAt){}
    public record Issued(Result body,String refreshCookie){}
    private record Credential(long user,long campus,String password,byte[] seed,boolean active){}
    private final JdbcTemplate jdbc;private final SensitiveDataCrypto crypto;private final IdentityService identity;private final OneTimeSecrets secrets;private final RateLimiter rates;private final Clock clock;private final ActorLoader loader;private final CurrentActor current;private final SessionTokenService sessions;private final SessionStore sessionStore;private final TransactionTemplate tx;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12);private final SecureRandom random=new SecureRandom();
    private final String dummyHash;
    public AdminAuthService(JdbcTemplate jdbc,SensitiveDataCrypto crypto,IdentityService identity,OneTimeSecrets secrets,RateLimiter rates,Clock clock,ActorLoader loader,CurrentActor current,SessionTokenService sessions,SessionStore sessionStore,PlatformTransactionManager manager){this.jdbc=jdbc;this.crypto=crypto;this.identity=identity;this.secrets=secrets;this.rates=rates;this.clock=clock;this.loader=loader;this.current=current;this.sessions=sessions;this.sessionStore=sessionStore;this.tx=new TransactionTemplate(manager);this.tx.setTimeout(10);this.dummyHash=passwords.encode(token());}
    public Challenge login(Password input,String ip){String username=text(input.username(),64,false);rates.check("admin-password-ip:"+identity.hash("admin-ip",ip),20,Duration.ofMinutes(1));rates.check("admin-password-user:"+identity.hash("admin-name",username),5,Duration.ofMinutes(5));
        var rows=jdbc.query("SELECT a.user_id,u.campus_id,a.password_hash,a.mfa_seed_encrypted,a.active FROM admin_credentials a JOIN users u ON u.id=a.user_id WHERE a.username=? AND u.deleted_at IS NULL",(r,i)->new Credential(r.getLong(1),r.getLong(2),r.getString(3),r.getBytes(4),r.getBoolean(5)),username);
        Credential credential=rows.isEmpty()?null:rows.getFirst();boolean matched=passwords.matches(text(input.password(),72,false),credential==null?dummyHash:credential.password());if(!matched||credential==null||!credential.active())throw failed();Actor actor=active(credential.user(),credential.campus());String token=token();Instant expiry=clock.instant().plusSeconds(300);
        if(!secrets.claim("admin-challenge",digest("challenge",token),actor.userId()+":"+actor.campusId()+":"+expiry.getEpochSecond(),Duration.ofMinutes(5)))throw failed();return new Challenge(token,true,expiry);}
    public Issued verify(Mfa input,String ip){rates.check("admin-mfa-ip:"+identity.hash("admin-ip",ip),20,Duration.ofMinutes(1));String saved=secrets.consume("admin-challenge",digest("challenge",text(input.challengeId(),128,false)));long[] binding=binding(saved);Actor actor=active(binding[0],binding[1]);verifyStep(actor,totp(input.code()),"admin.login");return issue(actor);}
    private void verifyStep(Actor actor,String code,String action){tx.executeWithoutResult(status->{Actor fresh=active(actor.userId(),actor.campusId());var seeds=jdbc.queryForList("SELECT mfa_seed_encrypted FROM admin_credentials WHERE user_id=? AND active=TRUE",byte[].class,fresh.userId());if(seeds.isEmpty())throw failed();long step=Totp.match(crypto.decrypt("admin-mfa",seeds.getFirst()),code,clock.instant().getEpochSecond());if(step<0||jdbc.update("UPDATE admin_credentials SET last_totp_step=? WHERE user_id=? AND active=TRUE AND last_totp_step<?",step,fresh.userId(),step)!=1)throw failed();identity.audit(fresh,action,"admin",fresh.userId(),null);});}
    public ReauthResult reauth(Reauth input){Actor actor=current.require();if(actor.kind()!=SessionKind.ADMIN)throw failed();rates.check("admin-reauth:"+actor.userId(),5,Duration.ofMinutes(5));verifyStep(actor,totp(input.mfaCode()),"admin.reauth");String token=token();Instant expiry=clock.instant().plusSeconds(300);if(!secrets.claim("admin-reauth",digest("reauth",token),actor.userId()+":"+actor.campusId()+":"+expiry.getEpochSecond(),Duration.ofMinutes(5)))throw failed();return new ReauthResult(token,expiry);}
    public void consumeReauth(String token,Actor actor){long[] saved;try{saved=binding(secrets.consume("admin-reauth",digest("reauth",token)));}catch(BusinessException e){if(e.getStatus()==503)throw e;throw IdentityService.forbidden();}if(saved[0]!=actor.userId()||saved[1]!=actor.campusId())throw IdentityService.forbidden();}
    public Issued refresh(String refresh){if(refresh==null||!refresh.matches("[A-Za-z0-9_-]{43}"))throw failed();try{String digest=HexFormat.of().formatHex(crypto.searchHash("session:"+SessionKind.ADMIN,refresh));SessionRecord record=sessionStore.find(digest,SessionKind.ADMIN).filter(r->r.refreshDigest().equals(digest)).orElseThrow(AdminAuthService::failed);Actor actor=active(record.userId(),record.campusId());return result(sessions.refresh(refresh,SessionKind.ADMIN),actor);}catch(org.springframework.dao.DataAccessException e){throw new BusinessException(503,"SESSION_UNAVAILABLE","会话服务暂时不可用",null,true);}}
    public void logout(String access){Actor actor=current.require();if(actor.kind()!=SessionKind.ADMIN)throw failed();tx.executeWithoutResult(status->identity.audit(actor,"admin.logout","admin",actor.userId(),null));sessions.revoke(access,SessionKind.ADMIN);}
    public Admin me(){Actor actor=current.require();if(actor.kind()!=SessionKind.ADMIN)throw failed();return admin(actor);}
    private Issued issue(Actor actor){return result(sessions.issue(actor.userId(),actor.campusId(),SessionKind.ADMIN),actor);}
    private Issued result(SessionTokens tokens,Actor actor){Admin admin=admin(actor);return new Issued(new Result(tokens.accessToken(),tokens.expiresIn(),null,tokens.refreshExpiresIn(),admin,actor.permissions()),tokens.refreshToken());}
    private Admin admin(Actor actor){var codes=jdbc.queryForList("SELECT r.code FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=? AND (ur.campus_id=? OR ur.campus_id=0)",String.class,actor.userId(),actor.campusId());return new Admin(Long.toString(actor.userId()),jdbc.queryForObject("SELECT nickname FROM users WHERE id=?",String.class,actor.userId()),Set.copyOf(codes),List.of(Long.toString(actor.campusId())),actor.permissions());}
    private Actor active(long user,long campus){if(jdbc.queryForObject("SELECT COUNT(*) FROM admin_credentials WHERE user_id=? AND active=TRUE",Integer.class,user)!=1)throw failed();return loader.load(user,campus,SessionKind.ADMIN).filter(a->a.kind()==SessionKind.ADMIN&&"active".equals(a.status())).orElseThrow(AdminAuthService::failed);}
    private long[] binding(String saved){try{if(saved==null)throw failed();String[] parts=saved.split(":");if(parts.length!=3)throw failed();long expiry=Long.parseLong(parts[2]);if(expiry<=clock.instant().getEpochSecond())throw failed();return new long[]{Long.parseLong(parts[0]),Long.parseLong(parts[1])};}catch(NumberFormatException e){throw failed();}}
    private String token(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private String digest(String kind,String token){if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw failed();return identity.hash("admin-"+kind,token);}
    private static BusinessException failed(){return new BusinessException(401,"ADMIN_AUTH_FAILED","管理员凭据无效");}
}
