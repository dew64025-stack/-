package com.caizhitong.moderation;

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
public class GovernanceService {
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    public GovernanceService(JdbcTemplate jdbc,CurrentActor current,SnowflakeIds ids,Clock clock,CursorCodec cursors,SensitiveDataCrypto crypto){
        this.jdbc=jdbc;this.current=current;this.ids=ids;this.clock=clock;this.cursors=cursors;this.crypto=crypto;
    }

    @BusinessWrite(student=true) public GovernanceViews.Report report(GovernanceInputs.Report input){
        Actor actor=current.requireStudent();
        long id=ids.nextId();LocalDateTime now=now();
        jdbc.update("INSERT INTO reports(id,campus_id,reporter_id,target_type,target_id,reason,status,created_at,updated_at) VALUES(?,?,?,?,?,?,'pending',?,?)",
                id,actor.campusId(),actor.userId(),text(input.targetType(),32,false),IdentityInputs.id(input.targetId()),text(input.reason(),512,false),now,now);
        return report(id);
    }

    @BusinessWrite public GovernanceViews.Blocked block(String targetType,long targetId){
        Actor actor=current.require();
        long id=ids.nextId();LocalDateTime now=now();
        jdbc.update("INSERT INTO blocks(id,blocker_id,target_type,target_id,created_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",id,actor.userId(),targetType,targetId,now);
        return new GovernanceViews.Blocked(true);
    }

    @BusinessWrite public GovernanceViews.Blocked unblock(String targetType,long targetId){
        Actor actor=current.require();
        jdbc.update("DELETE FROM blocks WHERE blocker_id=? AND target_type=? AND target_id=?",actor.userId(),targetType,targetId);
        return new GovernanceViews.Blocked(false);
    }

    @BusinessWrite(ownRights=true) public GovernanceViews.Appeal appeal(GovernanceInputs.Appeal input){
        Actor actor=current.requireAuthenticatedIdentity();
        long penaltyId=IdentityInputs.id(input.penaltyId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM penalties p JOIN users u ON u.id=p.user_id WHERE p.id=? AND p.user_id=? AND u.campus_id=?",Integer.class,penaltyId,actor.userId(),actor.campusId())!=1)throw missing();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM appeals WHERE penalty_id=? AND user_id=? AND status='pending'",Integer.class,penaltyId,actor.userId())>0)throw conflict();
        long id=ids.nextId();LocalDateTime now=now();
        jdbc.update("INSERT INTO appeals(id,user_id,penalty_id,reason,status,created_at) VALUES(?,?,?,?,'pending',?)",id,actor.userId(),penaltyId,text(input.reason(),512,false),now);
        return appeal(id);
    }

    public GovernanceViews.ReportPage myReports(String status,String cursor,Integer limit){
        Actor actor=current.require();int size=PageLimits.resolve(limit);
        String scope="reports-mine:"+actor.userId()+":"+status;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM reports WHERE reporter_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.userId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::report).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new GovernanceViews.ReportPage(page,next,more);
    }

    public GovernanceViews.Report reportDetail(long id){
        Actor actor=current.require();
        var r=report(id);
        if(jdbc.queryForObject("SELECT reporter_id FROM reports WHERE id=?",Long.class,id)!=actor.userId())throw missing();
        return r;
    }

    public GovernanceViews.PenaltyPage myPenalties(String cursor,Integer limit){
        Actor actor=current.requireAuthenticatedIdentity();int size=PageLimits.resolve(limit);
        String scope="penalties:"+actor.userId();
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        var found=jdbc.queryForList("SELECT id FROM penalties WHERE user_id=? AND id<? ORDER BY id DESC LIMIT ?",Long.class,actor.userId(),before,size+1);
        boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::penalty).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new GovernanceViews.PenaltyPage(page,next,more);
    }

    public GovernanceViews.AppealPage myAppeals(String cursor,Integer limit){
        Actor actor=current.requireAuthenticatedIdentity();int size=PageLimits.resolve(limit);
        String scope="appeals:"+actor.userId();
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        var found=jdbc.queryForList("SELECT id FROM appeals WHERE user_id=? AND id<? ORDER BY id DESC LIMIT ?",Long.class,actor.userId(),before,size+1);
        boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::appeal).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new GovernanceViews.AppealPage(page,next,more);
    }

    private GovernanceViews.Report report(long id){var rows=jdbc.query("SELECT id,target_type,target_id,reason,status,created_at,updated_at FROM reports WHERE id=?",(rs,i)->new GovernanceViews.Report(Long.toString(rs.getLong("id")),rs.getString("target_type"),Long.toString(rs.getLong("target_id")),rs.getString("reason"),rs.getString("status"),null,rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getObject("updated_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private GovernanceViews.Penalty penalty(long id){var rows=jdbc.query("SELECT id,penalty_type,reason,status,expires_at FROM penalties WHERE id=?",(rs,i)->new GovernanceViews.Penalty(Long.toString(rs.getLong("id")),rs.getString("penalty_type"),rs.getString("reason"),rs.getString("status"),rs.getObject("expires_at",LocalDateTime.class)==null?null:rs.getObject("expires_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private GovernanceViews.Appeal appeal(long id){var rows=jdbc.query("SELECT id,penalty_id,reason,status,reviewed_at,created_at FROM appeals WHERE id=?",(rs,i)->new GovernanceViews.Appeal(Long.toString(rs.getLong("id")),rs.getLong("penalty_id")==0?null:Long.toString(rs.getLong("penalty_id")),rs.getString("reason"),rs.getString("status"),rs.getObject("reviewed_at",LocalDateTime.class)==null?null:rs.getObject("reviewed_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),null,rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private BusinessException missing(){return new BusinessException(404,"NOT_FOUND","未找到请求的资源");}
    private BusinessException conflict(){return new BusinessException(409,"VERSION_CONFLICT","信息已更新，请刷新后重试");}
    private BusinessException invalid(){return new BusinessException(400,"VALIDATION_FAILED","请检查填写的信息");}
}
