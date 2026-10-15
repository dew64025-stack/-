package com.caizhitong.team;

import com.caizhitong.common.*;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.pagination.*;
import com.caizhitong.identity.IdentityInputs;
import com.caizhitong.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.*;

@Service
public class TeamService {
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    public TeamService(JdbcTemplate jdbc,CurrentActor current,SnowflakeIds ids,Clock clock,JsonMapper mapper,CursorCodec cursors,SensitiveDataCrypto crypto){
        this.jdbc=jdbc;this.current=current;this.ids=ids;this.clock=clock;this.mapper=mapper;this.cursors=cursors;this.crypto=crypto;
    }

    @BusinessWrite(student=true) public TeamViews.Team create(TeamInputs.Create input){
        Actor actor=current.requireStudent();
        long id=ids.nextId();LocalDateTime now=now();
        String openRoles=input.openRoles()==null?null:mapper.writeValueAsString(TeamInputs.checkOpenRoles(input.openRoles()));
        jdbc.update("INSERT INTO teams(id,campus_id,opportunity_id,leader_id,title,goal,open_roles_json,status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'recruiting',0,?,?)",
                id,actor.campusId(),input.opportunityId()==null||input.opportunityId().isNull()?null:IdentityInputs.id(input.opportunityId()),actor.userId(),
                text(input.title(),256,false),input.goal()==null?null:text(input.goal(),1024,true),openRoles,now,now);
        jdbc.update("INSERT INTO team_members(id,team_id,user_id,role,skill_tags,status,created_at) VALUES(?,?,?,NULL,NULL,'member',?)",ids.nextId(),id,actor.userId(),now);
        return team(id);
    }

    @BusinessWrite public TeamViews.Team update(long id,TeamInputs.Update input){
        Actor actor=current.require();authorizeLeader(actor,id);
        int actual=jdbc.queryForObject("SELECT version FROM teams WHERE id=? FOR UPDATE",Integer.class,id);
        if(actual!=IdentityInputs.version(input.version()))throw conflict();
        String openRoles=input.openRoles()==null?null:mapper.writeValueAsString(TeamInputs.checkOpenRoles(input.openRoles()));
        jdbc.update("UPDATE teams SET title=COALESCE(?,title),goal=COALESCE(?,goal),open_roles_json=COALESCE(?,open_roles_json),version=version+1,updated_at=? WHERE id=?",
                input.title()==null?null:text(input.title(),256,false),input.goal()==null?null:text(input.goal(),1024,true),openRoles,now(),id);
        return team(id);
    }

    @BusinessWrite(student=true) public TeamViews.Application apply(TeamInputs.Apply input){
        Actor actor=current.requireStudent();
        long teamId=IdentityInputs.id(input.teamId());
        var t=jdbc.queryForList("SELECT campus_id,status,leader_id FROM teams WHERE id=? FOR UPDATE",teamId);
        if(t.isEmpty())throw missing();
        if(!((Long)t.getFirst().get("campus_id")==actor.campusId()))throw missing();
        if(!t.getFirst().get("status").equals("recruiting"))throw conflict();
        if((Long)t.getFirst().get("leader_id")==actor.userId())throw conflict();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id=? AND user_id=?",Integer.class,teamId,actor.userId())>0)throw conflict();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM team_applications WHERE team_id=? AND user_id=? AND status='pending'",Integer.class,teamId,actor.userId())>0)throw conflict();
        long id=ids.nextId();LocalDateTime now=now();
        jdbc.update("INSERT INTO team_applications(id,team_id,user_id,message,status,created_at,updated_at) VALUES(?,?,?,?,'pending',?,?)",
                id,teamId,actor.userId(),input.message()==null?null:text(input.message(),512,true),now,now);
        return application(id);
    }

    @BusinessWrite public TeamViews.Application decide(long id,TeamInputs.Decision input){
        Actor actor=current.require();String decision=text(input.decision(),16,false);
        var a=jdbc.query("SELECT a.id,a.team_id,a.user_id,a.status,t.leader_id FROM team_applications a JOIN teams t ON t.id=a.team_id WHERE a.id=? FOR UPDATE",
                (rs,i)->new ApplyRow(rs.getLong("id"),rs.getLong("team_id"),rs.getLong("user_id"),rs.getLong("leader_id"),rs.getString("status")),id);
        if(a.isEmpty())throw missing();ApplyRow row=a.getFirst();
        if(row.leaderId()!=actor.userId())throw forbidden();
        if(!row.status().equals("pending"))throw conflict();
        LocalDateTime now=now();
        if(decision.equals("approve")){
            if(jdbc.queryForObject("SELECT status FROM teams WHERE id=? FOR UPDATE",String.class,row.teamId()).equals("closed"))throw conflict();
            jdbc.update("UPDATE team_applications SET status='approved',updated_at=? WHERE id=?",now,id);
            jdbc.update("INSERT INTO team_members(id,team_id,user_id,role,skill_tags,status,created_at) VALUES(?,?,?,NULL,NULL,'member',?)",ids.nextId(),row.teamId(),row.userId(),now);
        }else{
            jdbc.update("UPDATE team_applications SET status='rejected',updated_at=? WHERE id=?",now,id);
        }
        return application(id);
    }

    @BusinessWrite public TeamViews.Left leave(long id){
        Actor actor=current.require();
        var t=jdbc.queryForList("SELECT leader_id,status FROM teams WHERE id=? FOR UPDATE",id);
        if(t.isEmpty())throw missing();
        if((Long)t.getFirst().get("leader_id")==actor.userId())throw conflict();
        if(jdbc.update("DELETE FROM team_members WHERE team_id=? AND user_id=?",id,actor.userId())!=1)throw missing();
        return new TeamViews.Left(true);
    }

    @BusinessWrite public TeamViews.Team close(long id,TeamInputs.Close input){
        Actor actor=current.require();authorizeLeader(actor,id);
        int actual=jdbc.queryForObject("SELECT version FROM teams WHERE id=? FOR UPDATE",Integer.class,id);
        if(actual!=IdentityInputs.version(input.version()))throw conflict();
        jdbc.update("UPDATE teams SET status='closed',version=version+1,updated_at=? WHERE id=?",now(),id);
        return team(id);
    }

    public TeamViews.Page list(long campus,String q,String opportunityId,String skill,String cursor,Integer limit){
        int size=PageLimits.resolve(limit);q=q==null?"":q.trim();
        String scope="teams:"+campus+":"+opportunityId+":"+skill+":"+HexFormat.of().formatHex(crypto.searchHash("team-list",q));
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM teams WHERE campus_id=? AND status IN ('recruiting','full') AND deleted_at IS NULL AND id<?";
        List<Object> args=new ArrayList<>(List.of(campus,before));
        if(opportunityId!=null){sql+=" AND opportunity_id=?";args.add(id(opportunityId));}
        if(skill!=null){sql+=" AND open_roles_json LIKE ?";args.add("%"+escape(skill)+"%");}
        if(!q.isEmpty()){sql+=" AND title LIKE ? ESCAPE '!'";args.add("%"+escape(q)+"%");}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::team).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new TeamViews.Page(page,next,more);
    }

    public TeamViews.Detail detail(long id,long campus){
        Actor actor=current.optional().orElse(null);
        var row=jdbc.query("SELECT campus_id,leader_id,open_roles_json,status FROM teams WHERE id=?",(rs,i)->new Object[]{rs.getLong("campus_id"),rs.getLong("leader_id"),rs.getString("open_roles_json"),rs.getString("status")},id);
        if(row.isEmpty())throw missing();
        Object[] r=row.getFirst();
        if((Long)r[0]!=campus)throw missing();
        TeamViews.Team t=team(id);
        List<JsonNode> openRoles=new ArrayList<>();
        if(r[2]!=null){JsonNode n=mapper.readTree((String)r[2]);if(n.isArray())n.forEach(openRoles::add);}
        int memberCount=jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id=? AND status='member'",Integer.class,id);
        boolean isLeader=actor!=null&&actor.userId()==(Long)r[1];
        boolean isMember=actor!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id=? AND user_id=?",Integer.class,id,actor.userId())>0;
        boolean canApply=actor!=null&&"recruiting".equals(r[3])&&!isMember&&!isLeader;
        return new TeamViews.Detail(t,openRoles,memberCount,isLeader,isMember,canApply);
    }

    public TeamViews.Page myTeams(String cursor,Integer limit){
        Actor actor=current.require();int size=PageLimits.resolve(limit);
        String scope="teams-mine:"+actor.userId();
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        var found=jdbc.queryForList("SELECT team_id FROM team_members WHERE user_id=? AND status='member' AND team_id<? ORDER BY team_id DESC LIMIT ?",Long.class,actor.userId(),before,size+1);
        boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::team).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new TeamViews.Page(page,next,more);
    }

    public TeamViews.ApplicationPage myApplications(String status,String cursor,Integer limit){
        Actor actor=current.require();int size=PageLimits.resolve(limit);
        String scope="team-apps:"+actor.userId()+":"+status;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM team_applications WHERE user_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.userId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::application).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new TeamViews.ApplicationPage(page,next,more);
    }

    public TeamViews.ApplicationPage applications(long teamId,String status,String cursor,Integer limit){
        Actor actor=current.require();authorizeLeader(actor,teamId);int size=PageLimits.resolve(limit);
        String scope="team-applications:"+teamId+":"+status;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM team_applications WHERE team_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(teamId,before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::application).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new TeamViews.ApplicationPage(page,next,more);
    }

    private void authorizeLeader(Actor actor,long id){if(jdbc.queryForObject("SELECT COUNT(*) FROM teams WHERE id=? AND campus_id=? AND leader_id=?",Integer.class,id,actor.campusId(),actor.userId())!=1)throw missing();}
    private TeamViews.Team team(long id){var rows=jdbc.query("SELECT id,title,goal,status,version,leader_id,created_at FROM teams WHERE id=?",(rs,i)->new TeamViews.Team(Long.toString(rs.getLong("id")),rs.getString("title"),rs.getString("goal"),rs.getString("status"),rs.getInt("version"),Long.toString(rs.getLong("leader_id")),rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private TeamViews.Application application(long id){var rows=jdbc.query("SELECT id,team_id,message,status,created_at,updated_at FROM team_applications WHERE id=?",(rs,i)->new TeamViews.Application(Long.toString(rs.getLong("id")),Long.toString(rs.getLong("team_id")),rs.getString("message"),rs.getString("status"),rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getObject("updated_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private String escape(String text){return text.replace("!","!!").replace("%","!%").replace("_","!_");}
    private BusinessException missing(){return new BusinessException(404,"NOT_FOUND","未找到请求的资源");}
    private BusinessException conflict(){return new BusinessException(409,"VERSION_CONFLICT","信息已更新，请刷新后重试");}
    private BusinessException forbidden(){return new BusinessException(403,"FORBIDDEN","无权执行此操作");}
    private BusinessException invalid(){return new BusinessException(400,"VALIDATION_FAILED","请检查填写的信息");}
    private record ApplyRow(long id,long teamId,long userId,long leaderId,String status){}
}
