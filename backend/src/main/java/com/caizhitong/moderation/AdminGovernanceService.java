package com.caizhitong.moderation;

import com.caizhitong.admin.identity.AdminAuthService;
import com.caizhitong.common.*;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.pagination.*;
import com.caizhitong.identity.IdentityInputs;
import com.caizhitong.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.caizhitong.identity.IdentityInputs.*;

@Service
public class AdminGovernanceService {
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    private final AdminAuthService auth;
    public AdminGovernanceService(JdbcTemplate jdbc,CurrentActor current,SnowflakeIds ids,Clock clock,CursorCodec cursors,SensitiveDataCrypto crypto,AdminAuthService auth){
        this.jdbc=jdbc;this.current=current;this.ids=ids;this.clock=clock;this.cursors=cursors;this.crypto=crypto;this.auth=auth;
    }

    @BusinessWrite(permission="content.review") public GovernanceViews.ModerationTask claimModeration(long id){
        Actor actor=current.requirePermission("content.review");LocalDateTime now=now();
        if(jdbc.update("UPDATE moderation_tasks SET status='assigned',assigned_to=?,updated_at=? WHERE id=? AND campus_id=? AND status='pending'",actor.userId(),now,id,actor.campusId())!=1)throw conflict();
        return moderationTask(id);
    }

    @BusinessWrite(permission="content.review") public GovernanceViews.ModerationTask decideModeration(long id,GovernanceInputs.ModerationDecision input){
        Actor actor=current.requirePermission("content.review");String decision=text(input.decision(),16,false);
        var rows=jdbc.queryForList("SELECT target_type,target_id FROM moderation_tasks WHERE id=? AND campus_id=? AND status='assigned' AND assigned_to=? FOR UPDATE",id,actor.campusId(),actor.userId());
        if(rows.isEmpty())throw conflict();
        String targetType=(String)rows.getFirst().get("target_type");long targetId=(Long)rows.getFirst().get("target_id");
        String targetStatus=switch(decision){
            case "approve"->"published";
            case "reject"->"mutual_aid_post".equals(targetType)?"rejected":"deleted";
            case "restrict"->"community_post".equals(targetType)?"restricted":("mutual_aid_post".equals(targetType)?"rejected":"deleted");
            default->throw invalid();
        };
        applyTargetStatus(targetType,targetId,targetStatus);
        jdbc.update("UPDATE moderation_tasks SET status='resolved',result=?,updated_at=? WHERE id=?",decision,now(),id);
        return moderationTask(id);
    }

    @BusinessWrite(permission="report.review") public GovernanceViews.Report decideReport(long id,GovernanceInputs.ReportDecision input){
        Actor actor=current.requirePermission("report.review");String decision=text(input.decision(),16,false);
        var rows=jdbc.queryForList("SELECT status FROM reports WHERE id=? AND campus_id=? FOR UPDATE",id,actor.campusId());
        if(rows.isEmpty())throw missing();
        if(!rows.getFirst().get("status").equals("pending"))throw conflict();
        jdbc.update("UPDATE reports SET status=?,updated_at=? WHERE id=?",decision.equals("resolve")?"resolved":"dismissed",now(),id);
        return report(id);
    }

    @BusinessWrite(permission="user.penalty") public GovernanceViews.Penalty createPenalty(GovernanceInputs.Penalty input){
        Actor actor=current.requirePermission("user.penalty");
        long id=ids.nextId();LocalDateTime now=now();
        Instant expires=input.expiresAt()==null||input.expiresAt().isNull()?null:Instant.parse(text(input.expiresAt(),40,false));
        jdbc.update("INSERT INTO penalties(id,user_id,penalty_type,reason,expires_at,status,created_by,created_at) VALUES(?,?,?,?,?,'active',?,?)",
                id,IdentityInputs.id(input.userId()),text(input.type(),32,false),text(input.reason(),512,false),expires==null?null:LocalDateTime.ofInstant(expires,ZoneOffset.UTC),actor.userId(),now);
        return penalty(id);
    }

    @BusinessWrite(permission="user.penalty") public GovernanceViews.Penalty liftPenalty(long id,GovernanceInputs.Lift input){
        Actor actor=current.requirePermission("user.penalty");
        if(jdbc.update("UPDATE penalties SET status='lifted' WHERE id=? AND status='active'",id)!=1)throw conflict();
        return penalty(id);
    }

    @BusinessWrite(permission="appeal.review") public GovernanceViews.Appeal decideAppeal(long id,GovernanceInputs.Decision input){
        Actor actor=current.requirePermission("appeal.review");String decision=text(input.decision(),16,false);
        var rows=jdbc.queryForList("SELECT status,penalty_id FROM appeals WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())throw missing();
        if(!rows.getFirst().get("status").equals("pending"))throw conflict();
        if(decision.equals("approve")){
            Object pid=rows.getFirst().get("penalty_id");
            if(pid!=null)jdbc.update("UPDATE penalties SET status='lifted' WHERE id=?",(Long)pid);
        }
        jdbc.update("UPDATE appeals SET status=?,reviewed_by=?,reviewed_at=? WHERE id=?",decision.equals("approve")?"approved":"rejected",actor.userId(),now(),id);
        return appeal(id);
    }

    @BusinessWrite(permission="credit.review") public GovernanceViews.CreditDispute decideCreditDispute(long id,GovernanceInputs.Decision input){
        Actor actor=current.requirePermission("credit.review");String decision=text(input.decision(),16,false);
        var rows=jdbc.queryForList("SELECT status,campus_id,user_id,credit_event_id,reason FROM credit_disputes WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())throw missing();
        if(!((Long)rows.getFirst().get("campus_id")==actor.campusId()))throw missing();
        if(!rows.getFirst().get("status").equals("pending"))throw conflict();
        if(decision.equals("approve")){
            jdbc.update("INSERT INTO credit_events(id,campus_id,user_id,event_type,reason,points,related_type,related_id,created_at) VALUES(?,?,?,?,?,0,'credit_dispute',?,?)",
                    ids.nextId(),(Long)rows.getFirst().get("campus_id"),(Long)rows.getFirst().get("user_id"),"correction","信用纠错通过",(Long)rows.getFirst().get("credit_event_id"),now());
        }
        jdbc.update("UPDATE credit_disputes SET status=?,reviewed_by=?,reviewed_at=? WHERE id=?",decision.equals("approve")?"approved":"rejected",actor.userId(),now(),id);
        return creditDispute(id);
    }

    @BusinessWrite(permission="security.investigate") public GovernanceViews.Investigation investigate(GovernanceInputs.Investigation input){
        Actor actor=current.requirePermission("security.investigate");
        auth.consumeReauth(text(input.reauthToken(),128,false),actor);
        long targetId=IdentityInputs.id(input.targetId());String targetType=text(input.targetType(),32,false);
        if(!Set.of("community_post","community_comment").contains(targetType))throw invalid();
        byte[] encrypted="community_comment".equals(targetType)
                ?jdbc.queryForObject("SELECT user_id_encrypted FROM pseudo_identities WHERE comment_id=?",byte[].class,targetId)
                :jdbc.queryForObject("SELECT user_id_encrypted FROM pseudo_identities WHERE post_id=?",byte[].class,targetId);
        long userId=Long.parseLong(crypto.decrypt("anonymous-identity",encrypted));
        long investigationId=ids.nextId();
        jdbc.update("INSERT INTO security_investigations(id,investigator_id,reason,target_user_id,target_type,target_id,result_json,created_at) VALUES(?,?,?,?,?,?,?,?)",
                investigationId,actor.userId(),text(input.reason(),512,false),userId,targetType,targetId,"{}",now());
        return new GovernanceViews.Investigation(Long.toString(investigationId),new GovernanceViews.Subject(Long.toString(userId)),true);
    }

    public GovernanceViews.ModerationPage moderationTasks(String status,String targetType,String cursor,Integer limit){
        Actor actor=current.requirePermission("content.review");int size=PageLimits.resolve(limit);
        String scope="moderation:"+actor.campusId()+":"+status+":"+targetType;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM moderation_tasks WHERE campus_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.campusId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        if(targetType!=null){sql+=" AND target_type=?";args.add(targetType);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::moderationTask).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new GovernanceViews.ModerationPage(page,next,more);
    }

    public GovernanceViews.ReportPage adminReports(String status,String cursor,Integer limit){
        Actor actor=current.requirePermission("report.review");int size=PageLimits.resolve(limit);
        String scope="reports:"+actor.campusId()+":"+status;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM reports WHERE campus_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.campusId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::report).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new GovernanceViews.ReportPage(page,next,more);
    }

    public GovernanceViews.AppealPage adminAppeals(String status,String cursor,Integer limit){
        Actor actor=current.requirePermission("appeal.review");int size=PageLimits.resolve(limit);
        String scope="appeals:"+status;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM appeals WHERE id<?";
        List<Object> args=new ArrayList<>(List.of(before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::appeal).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new GovernanceViews.AppealPage(page,next,more);
    }

    public GovernanceViews.CreditDisputePage creditDisputes(String status,String cursor,Integer limit){
        Actor actor=current.requirePermission("credit.review");int size=PageLimits.resolve(limit);
        String scope="credit-disputes:"+actor.campusId()+":"+status;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM credit_disputes WHERE campus_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.campusId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::creditDispute).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new GovernanceViews.CreditDisputePage(page,next,more);
    }

    private void applyTargetStatus(String targetType,long targetId,String status){
        switch(targetType){
            case "mutual_aid_post"->jdbc.update("UPDATE mutual_aid_posts SET status=?,updated_at=? WHERE id=?",status,now(),targetId);
            case "community_post"->jdbc.update("UPDATE community_posts SET status=?,updated_at=? WHERE id=?",status,now(),targetId);
            case "community_comment"->jdbc.update("UPDATE community_comments SET status=? WHERE id=?",status,targetId);
            default->throw invalid();
        }
    }
    private GovernanceViews.ModerationTask moderationTask(long id){var rows=jdbc.query("SELECT id,target_type,target_id,status,result,created_at FROM moderation_tasks WHERE id=?",(rs,i)->new GovernanceViews.ModerationTask(Long.toString(rs.getLong("id")),rs.getString("target_type"),Long.toString(rs.getLong("target_id")),rs.getString("status"),rs.getString("result"),rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private GovernanceViews.Report report(long id){var rows=jdbc.query("SELECT id,target_type,target_id,reason,status,created_at,updated_at FROM reports WHERE id=?",(rs,i)->new GovernanceViews.Report(Long.toString(rs.getLong("id")),rs.getString("target_type"),Long.toString(rs.getLong("target_id")),rs.getString("reason"),rs.getString("status"),null,rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getObject("updated_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private GovernanceViews.Penalty penalty(long id){var rows=jdbc.query("SELECT id,penalty_type,reason,status,expires_at FROM penalties WHERE id=?",(rs,i)->new GovernanceViews.Penalty(Long.toString(rs.getLong("id")),rs.getString("penalty_type"),rs.getString("reason"),rs.getString("status"),rs.getObject("expires_at",LocalDateTime.class)==null?null:rs.getObject("expires_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private GovernanceViews.Appeal appeal(long id){var rows=jdbc.query("SELECT id,penalty_id,reason,status,reviewed_at,created_at FROM appeals WHERE id=?",(rs,i)->new GovernanceViews.Appeal(Long.toString(rs.getLong("id")),rs.getLong("penalty_id")==0?null:Long.toString(rs.getLong("penalty_id")),rs.getString("reason"),rs.getString("status"),rs.getObject("reviewed_at",LocalDateTime.class)==null?null:rs.getObject("reviewed_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),null,rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private GovernanceViews.CreditDispute creditDispute(long id){var rows=jdbc.query("SELECT id,user_id,credit_event_id,reason,status,created_at FROM credit_disputes WHERE id=?",(rs,i)->new GovernanceViews.CreditDispute(Long.toString(rs.getLong("id")),Long.toString(rs.getLong("user_id")),Long.toString(rs.getLong("credit_event_id")),rs.getString("reason"),rs.getString("status"),rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private BusinessException missing(){return new BusinessException(404,"NOT_FOUND","未找到请求的资源");}
    private BusinessException conflict(){return new BusinessException(409,"VERSION_CONFLICT","信息已更新，请刷新后重试");}
    private BusinessException invalid(){return new BusinessException(400,"VALIDATION_FAILED","请检查填写的信息");}
}
