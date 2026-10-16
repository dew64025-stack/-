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
    private final InvestigationGateway investigations;
    private final com.caizhitong.common.idempotency.IdempotencyExecutor idempotency;
    public AdminGovernanceService(JdbcTemplate jdbc,CurrentActor current,SnowflakeIds ids,Clock clock,CursorCodec cursors,SensitiveDataCrypto crypto,AdminAuthService auth,
            @org.springframework.beans.factory.annotation.Qualifier("investigationGateway") InvestigationGateway investigations,com.caizhitong.common.idempotency.IdempotencyExecutor idempotency){
        this.jdbc=jdbc;this.current=current;this.ids=ids;this.clock=clock;this.cursors=cursors;this.crypto=crypto;this.auth=auth;this.investigations=investigations;this.idempotency=idempotency;
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
        String targetType=(String)rows.getFirst().get("target_type");long targetId=((Number)rows.getFirst().get("target_id")).longValue();
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
        long userId=IdentityInputs.id(input.userId());
        if(jdbc.queryForList("SELECT id FROM users WHERE id=? AND campus_id=? AND deleted_at IS NULL FOR UPDATE",Long.class,userId,actor.campusId()).isEmpty())throw missing();
        if(input.relatedReportId()!=null&&!input.relatedReportId().isNull()&&jdbc.queryForObject("SELECT COUNT(*) FROM reports WHERE id=? AND campus_id=?",Integer.class,IdentityInputs.id(input.relatedReportId()),actor.campusId())!=1)throw missing();
        long id=ids.nextId();LocalDateTime now=now();
        Instant expires=input.expiresAt()==null||input.expiresAt().isNull()?null:Instant.parse(text(input.expiresAt(),40,false));
        jdbc.update("INSERT INTO penalties(id,user_id,penalty_type,reason,expires_at,status,created_by,created_at) VALUES(?,?,?,?,?,'active',?,?)",
                id,userId,text(input.type(),32,false),text(input.reason(),512,false),expires==null?null:LocalDateTime.ofInstant(expires,ZoneOffset.UTC),actor.userId(),now);
        audit(actor,"penalty.create","penalty",id,text(input.reason(),512,false));
        return penalty(id);
    }

    @BusinessWrite(permission="user.penalty") public GovernanceViews.Penalty liftPenalty(long id,GovernanceInputs.Lift input){
        Actor actor=current.requirePermission("user.penalty");
        if(jdbc.queryForList("SELECT p.id FROM penalties p JOIN users u ON u.id=p.user_id WHERE p.id=? AND u.campus_id=? FOR UPDATE",Long.class,id,actor.campusId()).isEmpty())throw missing();
        if(jdbc.update("UPDATE penalties SET status='lifted' WHERE id=? AND status='active'",id)!=1)throw conflict();
        audit(actor,"penalty.lift","penalty",id,text(input.reason(),512,false));
        return penalty(id);
    }

    @BusinessWrite(permission="appeal.review") public GovernanceViews.Appeal decideAppeal(long id,GovernanceInputs.Decision input){
        Actor actor=current.requirePermission("appeal.review");String decision=text(input.decision(),16,false);
        var rows=jdbc.queryForList("SELECT a.status,a.penalty_id FROM appeals a JOIN users u ON u.id=a.user_id LEFT JOIN penalties p ON p.id=a.penalty_id WHERE a.id=? AND u.campus_id=? AND (a.penalty_id IS NULL OR p.user_id=a.user_id) FOR UPDATE",id,actor.campusId());
        if(rows.isEmpty())throw missing();
        if(!rows.getFirst().get("status").equals("pending"))throw conflict();
        if(decision.equals("approve")){
            Object pid=rows.getFirst().get("penalty_id");
            if(pid!=null)jdbc.update("UPDATE penalties SET status='lifted' WHERE id=?",((Number)pid).longValue());
        }
        jdbc.update("UPDATE appeals SET status=?,reviewed_by=?,reviewed_at=? WHERE id=?",decision.equals("approve")?"approved":"rejected",actor.userId(),now(),id);
        audit(actor,"appeal.decision","appeal",id,text(input.reason(),512,false));
        return appeal(id);
    }

    @BusinessWrite(permission="credit.review") public GovernanceViews.CreditDispute decideCreditDispute(long id,GovernanceInputs.Decision input){
        Actor actor=current.requirePermission("credit.review");String decision=text(input.decision(),16,false);
        var rows=jdbc.queryForList("SELECT d.status,d.campus_id,d.user_id,d.credit_event_id,d.reason FROM credit_disputes d JOIN users u ON u.id=d.user_id AND u.campus_id=d.campus_id JOIN credit_events e ON e.id=d.credit_event_id AND e.campus_id=d.campus_id AND e.user_id=d.user_id WHERE d.id=? AND d.campus_id=? FOR UPDATE",id,actor.campusId());
        if(rows.isEmpty())throw missing();
        if(!rows.getFirst().get("status").equals("pending"))throw conflict();
        if(decision.equals("approve")){
            jdbc.update("INSERT INTO credit_events(id,campus_id,user_id,event_type,reason,points,related_type,related_id,created_at) VALUES(?,?,?,?,?,0,'credit_dispute',?,?)",
                    ids.nextId(),((Number)rows.getFirst().get("campus_id")).longValue(),((Number)rows.getFirst().get("user_id")).longValue(),"correction",text(input.reason(),512,false),((Number)rows.getFirst().get("credit_event_id")).longValue(),now());
        }
        jdbc.update("UPDATE credit_disputes SET status=?,reviewed_by=?,reviewed_at=? WHERE id=?",decision.equals("approve")?"approved":"rejected",actor.userId(),now(),id);
        audit(actor,"credit.dispute.decision","credit_dispute",id,text(input.reason(),512,false));
        return creditDispute(id);
    }

    public GovernanceViews.Investigation investigate(GovernanceInputs.Investigation input,String reauth,com.caizhitong.common.idempotency.IdempotencyContext context){
        Actor actor=current.requirePermission("security.investigate");
        long targetId=IdentityInputs.id(input.targetId());String targetType=text(input.targetType(),32,false);
        if(!Set.of("community_post","community_comment").contains(targetType))throw invalid();
        Long report=input.reportId()==null||input.reportId().isNull()?null:IdentityInputs.id(input.reportId());
        authorizeInvestigation(actor,targetType,targetId,report);
        if(reauth==null||reauth.isBlank())throw new BusinessException(403,"FORBIDDEN","需要再次认证");
        investigations.requireAvailable();
        auth.consumeReauth(reauth,actor);
        var receipt=idempotency.execute(context,GovernanceViews.InvestigationReceipt.class,
            ()->authorizeInvestigation(current.requirePermission("security.investigate"),targetType,targetId,report),
            ()->new GovernanceViews.InvestigationReceipt(Long.toString(ids.nextId())));
        actor=current.requirePermission("security.investigate");
        authorizeInvestigation(actor,targetType,targetId,report);
        return investigations.disclose(actor,receipt.investigationId(),targetType,targetId,report,text(input.reason(),512,false));
    }

    private void authorizeInvestigation(Actor actor,String type,long target,Long report){
        String sql="community_post".equals(type)?"SELECT COUNT(*) FROM community_posts WHERE id=? AND campus_id=?":"SELECT COUNT(*) FROM community_comments c JOIN community_posts p ON p.id=c.post_id WHERE c.id=? AND p.campus_id=?";
        if(jdbc.queryForObject(sql,Integer.class,target,actor.campusId())!=1)throw missing();
        if(report!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM reports WHERE id=? AND campus_id=? AND target_type=? AND target_id=?",Integer.class,report,actor.campusId(),type,target)!=1)throw missing();
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
        String scope="appeals:"+actor.campusId()+":"+status;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM appeals WHERE user_id IN (SELECT id FROM users WHERE campus_id=?) AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.campusId(),before));
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
    private void audit(Actor actor,String action,String type,long target,String reason){jdbc.update("INSERT INTO admin_audit_logs(id,operator_id,action,target_type,target_id,reason,detail_json,created_at) VALUES(?,?,?,?,?,?,'{}',?)",ids.nextId(),actor.userId(),action,type,target,reason,now());}
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
