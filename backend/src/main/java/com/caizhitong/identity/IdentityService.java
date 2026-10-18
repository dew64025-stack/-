package com.caizhitong.identity;

import com.caizhitong.common.*;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.pagination.*;
import com.caizhitong.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.*;
import static com.caizhitong.identity.IdentityViews.*;

@Service public class IdentityService {
    private final JdbcTemplate jdbc;private final CurrentActor current;private final SensitiveDataCrypto crypto;private final SnowflakeIds ids;private final JsonMapper mapper;private final Clock clock;private final CursorCodec cursors;
    private final com.caizhitong.material.files.AvatarUrls avatarUrls;
    public IdentityService(JdbcTemplate jdbc,CurrentActor current,SensitiveDataCrypto crypto,SnowflakeIds ids,JsonMapper mapper,Clock clock,CursorCodec cursors,com.caizhitong.material.files.AvatarUrls avatarUrls){this.jdbc=jdbc;this.current=current;this.crypto=crypto;this.ids=ids;this.mapper=mapper;this.clock=clock;this.cursors=cursors;this.avatarUrls=avatarUrls;}
    public User me(){return user(current.require());}
    public User user(Actor actor){User row=jdbc.queryForObject("SELECT id,campus_id,nickname,avatar_url,status,version FROM users WHERE id=? AND campus_id=? AND deleted_at IS NULL",(r,i)->new User(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),false,null,null,r.getInt(6)),actor.userId(),actor.campusId());return new User(row.id(),row.campusId(),row.nickname(),row.avatarUrl(),row.status(),actor.phoneBound(),maskedPhone(actor.userId(),actor.phoneBound()),verification(actor.userId(),actor.campusId()).status(),row.version());}
    private String maskedPhone(long user,boolean bound){if(!bound)return null;var values=jdbc.queryForList("SELECT phone_encrypted FROM user_phones WHERE user_id=? AND verified=1",byte[].class,user);return values.isEmpty()?null:mask(crypto.decrypt("phone",values.getFirst()));}
    public static String mask(String value){return value.length()<=4?"****":"****"+value.substring(value.length()-4);}
    public Profile profile(){return profile(current.require().userId());}
    private Profile profile(long user){var rows=jdbc.query("SELECT college,grade,skills,interests,version FROM user_profiles WHERE user_id=?",(r,i)->new Profile(r.getString(1),r.getString(2),array(r.getString(3)),array(r.getString(4)),r.getInt(5)),user);return rows.isEmpty()?new Profile(null,null,List.of(),List.of(),0):rows.getFirst();}
    private List<String> array(String json){if(json==null)return List.of();var node=mapper.readTree(json);if(node.isString())node=mapper.readTree(node.asString());return strings(node);}
    private void lock(Actor actor){if(jdbc.queryForList("SELECT id FROM users WHERE id=? AND campus_id=? AND deleted_at IS NULL FOR UPDATE",Long.class,actor.userId(),actor.campusId()).isEmpty())throw forbidden();}
    @BusinessWrite public User update(Update input){Actor actor=current.require();lock(actor);int version=version(input.version());String nickname=input.nickname()==null?me().nickname():text(input.nickname(),64,false);User old=me();String url=input.avatarAttachmentId()==null?old.avatarUrl():null;
        if(jdbc.update("UPDATE users SET nickname=?,avatar_url=?,avatar_attachment_id=CASE WHEN ? THEN ? ELSE avatar_attachment_id END,version=version+1,updated_at=? WHERE id=? AND campus_id=? AND version=?",nickname,url,input.avatarAttachmentId()!=null,input.avatarAttachmentId()==null||input.avatarAttachmentId().isNull()?null:id(input.avatarAttachmentId()),now(),actor.userId(),actor.campusId(),version)!=1)throw conflict();User value=me();return new User(value.id(),value.campusId(),value.nickname(),null,value.status(),value.phoneBound(),value.phoneMasked(),value.verificationStatus(),value.version());}
    /** Avatar access URLs are hydrated outside the business proxy and never enter persisted replay DTOs. */
    public User userResponse(User value) {
        Actor actor=current.require();
        if(!Long.toString(actor.userId()).equals(value.id())||!Long.toString(actor.campusId()).equals(value.campusId()))throw forbidden();
        var row=jdbc.queryForMap("SELECT avatar_attachment_id,avatar_url FROM users WHERE id=? AND campus_id=?",actor.userId(),actor.campusId());
        String url=row.get("avatar_attachment_id")==null?(String)row.get("avatar_url"):
                avatarUrls.avatarUrl(actor,((Number)row.get("avatar_attachment_id")).longValue());
        return new User(value.id(),value.campusId(),value.nickname(),url,value.status(),value.phoneBound(),value.phoneMasked(),value.verificationStatus(),value.version());
    }
    @BusinessWrite public Profile updateProfile(ProfileUpdate input){Actor actor=current.require();lock(actor);Profile old=profile(actor.userId());if(old.version()!=version(input.version()))throw conflict();
        String college=input.college()==null?old.college():text(input.college(),128,true),grade=input.grade()==null?old.grade():text(input.grade(),32,true);
        if(college!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM campus_organizations WHERE campus_id=? AND name=? AND type='college' AND status='active' AND deleted_at IS NULL",Integer.class,actor.campusId(),college)==0)throw invalid();
        if(grade!=null){var allowed=jdbc.queryForList("SELECT config_value FROM campus_configs WHERE campus_id=? AND config_key='profile.allowedGrades'",String.class,actor.campusId());if(allowed.isEmpty()||!array(allowed.getFirst()).contains(grade))throw invalid();}
        List<String> skills=input.skills()==null?old.skills():strings(input.skills()),interests=input.interests()==null?old.interests():strings(input.interests());
        int changed=jdbc.update("UPDATE user_profiles SET college=?,grade=?,skills=?,interests=?,version=version+1,updated_at=? WHERE user_id=? AND version=?",college,grade,mapper.writeValueAsString(skills),mapper.writeValueAsString(interests),now(),actor.userId(),old.version());
        if(changed==0){if(old.version()!=0)throw conflict();jdbc.update("INSERT INTO user_profiles(id,user_id,college,grade,skills,interests,version,created_at,updated_at) VALUES(?,?,?,?,?,?,1,?,?)",ids.nextId(),actor.userId(),college,grade,mapper.writeValueAsString(skills),mapper.writeValueAsString(interests),now(),now());}return profile(actor.userId());}
    public Verification verification(){Actor actor=current.require();return verification(actor.userId(),actor.campusId());}
    private static final String VERIFICATION_SELECT="SELECT v.id,v.status,v.student_id_encrypted,v.reject_reason,v.created_at,v.version,CASE WHEN EXISTS(SELECT 1 FROM student_roster s WHERE s.campus_id=v.campus_id AND s.student_id_search=v.student_id_search AND s.status='active') THEN 1 ELSE 0 END matched FROM student_verifications v ";
    private Verification verificationRow(java.sql.ResultSet r,int index)throws java.sql.SQLException{return new Verification(r.getString(1),r.getString(2),mask(crypto.decrypt("student",r.getBytes(3))),r.getString(4),r.getObject(5,LocalDateTime.class).toInstant(ZoneOffset.UTC),r.getInt(6),r.getBoolean(7));}
    public Verification verification(long user,long campus){var rows=jdbc.query(VERIFICATION_SELECT+"WHERE v.user_id=? AND v.campus_id=? ORDER BY v.created_at DESC,v.id DESC LIMIT 1",this::verificationRow,user,campus);return rows.isEmpty()?new Verification(null,"unverified",null,null,null,0,false):rows.getFirst();}
    @BusinessWrite public Verification apply(Student input){Actor actor=current.require();lock(actor);if(!current.require().phoneBound())throw forbidden();String number=text(input.studentNumber(),32,false),hash=hash("student",number);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM student_verifications WHERE (user_id=? AND status IN ('pending','verified')) OR (campus_id=? AND student_id_search=? AND status='verified')",Integer.class,actor.userId(),actor.campusId(),hash)>0)throw conflict();
        try{jdbc.update("INSERT INTO student_verifications(id,user_id,campus_id,student_id_encrypted,student_id_search,status,version,created_at,updated_at) VALUES(?,?,?,?,?,'pending',0,?,?)",ids.nextId(),actor.userId(),actor.campusId(),crypto.encrypt("student",number),hash,now(),now());}catch(DuplicateKeyException e){throw conflict();}return verification();}
    public long authorizeReview(long id){Actor actor=current.requirePermission("identity.review");var rows=jdbc.queryForList("SELECT id FROM student_verifications WHERE id=? AND campus_id=?",Long.class,id,actor.campusId());if(rows.isEmpty())throw new BusinessException(404,"NOT_FOUND","未找到认证申请");return actor.campusId();}
    @BusinessWrite(permission="identity.review") public Verification decide(long id,Decision input){Actor actor=current.requirePermission("identity.review");authorizeReview(id);var rows=jdbc.queryForList("SELECT user_id,status,version FROM student_verifications WHERE id=? AND campus_id=? FOR UPDATE",id,actor.campusId());var row=rows.getFirst();long user=((Number)row.get("user_id")).longValue();lock(new Actor(user,actor.campusId(),Set.of(),false,false,"active",SessionKind.STUDENT));
        if(!"pending".equals(row.get("status"))||((Number)row.get("version")).intValue()!=version(input.version()))throw conflict();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_phones p WHERE p.user_id=? AND p.verified=1 AND EXISTS(SELECT 1 FROM authorizations a WHERE a.user_id=p.user_id AND a.type='phone' AND a.status='granted' AND a.revoked_at IS NULL AND a.id=(SELECT latest.id FROM authorizations latest WHERE latest.user_id=p.user_id AND latest.type='phone' ORDER BY latest.created_at DESC,latest.id DESC LIMIT 1))",Integer.class,user)==0)throw forbidden();
        String status=text(input.decision(),16,false).equals("approve")?"verified":"rejected";
        try{jdbc.update("UPDATE student_verifications SET status=?,verified_at=?,reviewer_id=?,reject_reason=?,version=version+1,updated_at=? WHERE id=?",status,status.equals("verified")?now():null,actor.userId(),status.equals("rejected")?text(input.reason(),255,false):null,now(),id);}catch(DuplicateKeyException e){throw conflict();}
        audit(actor,"identity."+status,"student_verification",id,text(input.reason(),512,false));return verification(user,actor.campusId());}
    public Page<Verification> queue(String status,String cursor,int limit){Actor actor=current.requirePermission("identity.review");if(status!=null&&!Set.of("pending","verified","rejected").contains(status))throw invalid();String scope="identity-queue:"+actor.campusId()+":"+(status==null?"":status);long after=after(cursor,scope);checkLimit(limit);var rows=jdbc.query(VERIFICATION_SELECT+"WHERE v.campus_id=? AND v.id<? AND (? IS NULL OR v.status=?) ORDER BY v.id DESC LIMIT ?",this::verificationRow,actor.campusId(),after,status,status,limit+1);var items=rows.stream().limit(limit).toList();return new Page<>(items,rows.size()>limit?next(scope,items.getLast().id()):null,rows.size()>limit);}
    public Page<User> users(String q,String status,String cursor,int limit){Actor actor=current.requirePermission("user.read");if(q!=null&&(q.length()>64||q.contains("%")||q.contains("_")))throw invalid();if(status!=null&&!Set.of("active","banned","disabled").contains(status))throw invalid();checkLimit(limit);String scope="identity-users:"+actor.campusId()+":"+hash("identity-user-filter",mapper.writeValueAsString(Arrays.asList(q,status)));long after=after(cursor,scope);var ids=jdbc.queryForList("SELECT id FROM users WHERE campus_id=? AND deleted_at IS NULL AND id<? AND (? IS NULL OR status=?) AND (? IS NULL OR nickname LIKE ?) ORDER BY id DESC LIMIT ?",Long.class,actor.campusId(),after,status,status,q,q==null?null:"%"+q+"%",limit+1);var items=ids.stream().limit(limit).map(user->user(new Actor(user,actor.campusId(),Set.of(),currentPhone(user),false,"active",SessionKind.STUDENT))).toList();return new Page<>(items,ids.size()>limit?next(scope,items.getLast().id()):null,ids.size()>limit);}
    private boolean currentPhone(long user){return jdbc.queryForObject("SELECT COUNT(*) FROM user_phones p WHERE p.user_id=? AND p.verified=1 AND EXISTS(SELECT 1 FROM authorizations a WHERE a.user_id=p.user_id AND a.type='phone' AND a.status='granted' AND a.revoked_at IS NULL AND a.id=(SELECT latest.id FROM authorizations latest WHERE latest.user_id=p.user_id AND latest.type='phone' ORDER BY latest.created_at DESC,latest.id DESC LIMIT 1))",Integer.class,user)>0;}
    private long after(String cursor,String scope){if(cursor==null)return Long.MAX_VALUE;var keys=cursors.decode(cursor,scope).sortKeys();if(keys.size()!=1||!keys.containsKey("id"))throw invalid();return id(keys.get("id"));}
    private String next(String scope,String id){return cursors.encode(new CursorPayload(scope,Map.of("id",id)));}
    public void audit(Actor actor,String action,String type,long target,String reason){jdbc.update("INSERT INTO admin_audit_logs(id,operator_id,campus_id,action,target_type,target_id,reason,detail_json,created_at) VALUES(?,?,?,?,?,?,?,'{}',?)",ids.nextId(),actor.userId(),actor.campusId(),action,type,target,reason,now());}
    public String hash(String purpose,String value){return HexFormat.of().formatHex(crypto.searchHash(purpose,value));}
    public LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    public static BusinessException forbidden(){return new BusinessException(403,"FORBIDDEN","无权执行此操作");}
    public static BusinessException conflict(){return new BusinessException(409,"VERSION_CONFLICT","信息已更新或身份归属冲突，请刷新后重试");}
    private void checkLimit(int limit){if(limit<1||limit>100)throw invalid();}
}
