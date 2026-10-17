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
        replaceRoles(id,input.openRoles());
        return team(id);
    }

    @BusinessWrite public TeamViews.Team update(long id,TeamInputs.Update input){
        Actor actor=current.require();TeamRow locked=lockTeam(id);authorizeLeader(actor,locked);if(locked.status().equals("closed"))throw conflict();
        if(locked.version()!=IdentityInputs.version(input.version()))throw conflict();
        if(input.openRoles()!=null)replaceRoles(id,input.openRoles());
        String openRoles=input.openRoles()==null?null:mapper.writeValueAsString(TeamInputs.checkOpenRoles(input.openRoles()));
        jdbc.update("UPDATE teams SET title=COALESCE(?,title),goal=COALESCE(?,goal),open_roles_json=COALESCE(?,open_roles_json),version=version+1,updated_at=? WHERE id=?",
                input.title()==null?null:text(input.title(),256,false),input.goal()==null?null:text(input.goal(),1024,true),openRoles,now(),id);
        refreshStatus(id);return team(id);
    }

    @BusinessWrite(student=true) public TeamViews.Application apply(TeamInputs.Apply input){
        Actor actor=current.requireStudent();
        long teamId=IdentityInputs.id(input.teamId());
        TeamRow t=lockTeam(teamId);
        if(t.campusId()!=actor.campusId())throw missing();
        if(!t.status().equals("recruiting"))throw conflict();
        if(t.leaderId()==actor.userId())throw conflict();
        long role=resolveRole(teamId,input.openRoleId());checkCapacity(t,role);
        if(!jdbc.queryForList("SELECT id FROM team_members WHERE team_id=? AND user_id=? FOR UPDATE",Long.class,teamId,actor.userId()).isEmpty())throw conflict();
        if(!jdbc.queryForList("SELECT id FROM team_applications WHERE team_id=? AND user_id=? AND status='pending' FOR UPDATE",Long.class,teamId,actor.userId()).isEmpty())throw conflict();
        long id=ids.nextId();LocalDateTime now=now();
        jdbc.update("INSERT INTO team_applications(id,team_id,user_id,open_role_id,message,status,created_at,updated_at) VALUES(?,?,?,?,?,'pending',?,?)",
                id,teamId,actor.userId(),role,input.message()==null?null:text(input.message(),512,true),now,now);
        return application(id);
    }

    @BusinessWrite public TeamViews.Application decide(long id,TeamInputs.Decision input){
        Actor actor=current.require();String decision=text(input.decision(),16,false);
        var teamIds=jdbc.queryForList("SELECT team_id FROM team_applications WHERE id=?",Long.class,id);if(teamIds.isEmpty())throw missing();
        TeamRow team=lockTeam(teamIds.getFirst());authorizeLeader(actor,team);if(team.status().equals("closed"))throw conflict();
        var a=jdbc.query("SELECT id,team_id,user_id,status,open_role_id FROM team_applications WHERE id=? FOR UPDATE",
                (rs,i)->new ApplyRow(rs.getLong("id"),rs.getLong("team_id"),rs.getLong("user_id"),rs.getString("status"),rs.getString("open_role_id")),id);
        if(a.isEmpty())throw missing();ApplyRow row=a.getFirst();
        if(!row.status().equals("pending"))throw conflict();
        LocalDateTime now=now();
        if(decision.equals("approve")){
            if(row.openRoleId()==null)throw repairRequired();
            if(!team.status().equals("recruiting"))throw conflict();
            if(!jdbc.queryForList("SELECT id FROM team_members WHERE team_id=? AND user_id=? FOR UPDATE",Long.class,row.teamId(),row.userId()).isEmpty())throw conflict();
            long role=resolveRole(row.teamId(),mapper.valueToTree(row.openRoleId()));checkCapacity(team,role);
            jdbc.update("UPDATE team_applications SET status='approved',updated_at=? WHERE id=?",now,id);
            jdbc.update("INSERT INTO team_members(id,team_id,user_id,open_role_id,role,skill_tags,status,created_at) SELECT ?,?,?,id,role,skill_tags,'member',? FROM team_open_roles WHERE id=? AND team_id=?",ids.nextId(),row.teamId(),row.userId(),now,role,row.teamId());
            refreshStatus(row.teamId());
        }else{
            jdbc.update("UPDATE team_applications SET status='rejected',updated_at=? WHERE id=?",now,id);
        }
        return application(id);
    }

    @BusinessWrite public TeamViews.Left leave(long id){
        Actor actor=current.require();
        TeamRow t=lockTeam(id);if(t.campusId()!=actor.campusId())throw missing();if(t.status().equals("closed"))throw conflict();
        if(t.leaderId()==actor.userId())throw conflict();
        if(jdbc.update("DELETE FROM team_members WHERE team_id=? AND user_id=?",id,actor.userId())!=1)throw missing();
        refreshStatus(id);return new TeamViews.Left(true);
    }

    @BusinessWrite public TeamViews.Team close(long id,TeamInputs.Close input){
        Actor actor=current.require();TeamRow locked=lockTeam(id);authorizeLeader(actor,locked);if(locked.status().equals("closed"))throw conflict();
        if(locked.version()!=IdentityInputs.version(input.version()))throw conflict();
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
        openRoles.addAll(roles(id));
        int memberCount=jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id=? AND status='member'",Integer.class,id);
        boolean isLeader=actor!=null&&actor.userId()==(Long)r[1];
        boolean isMember=actor!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id=? AND user_id=?",Integer.class,id,actor.userId())>0;
        boolean canApply=actor!=null&&actor.campusId()==campus&&"recruiting".equals(r[3])&&!openRoles.isEmpty()&&!isMember&&!isLeader;
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
    private TeamViews.Team team(long id){var rows=jdbc.query("SELECT * FROM teams WHERE id=?",(rs,i)->new TeamViews.Team(rs.getString("id"),rs.getString("opportunity_id"),rs.getString("title"),rs.getString("goal"),rs.getString("status"),rs.getInt("version"),person(rs.getLong("leader_id")),members(id),rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private List<TeamViews.Member> members(long id){return jdbc.query("SELECT * FROM team_members WHERE team_id=? ORDER BY id",(rs,i)->new TeamViews.Member(rs.getString("id"),rs.getString("user_id"),rs.getString("role"),tags(rs.getString("skill_tags")),rs.getString("status"),rs.getString("open_role_id")),id);}
    private List<String> tags(String json){if(json==null)return List.of();List<String> result=new ArrayList<>();mapper.readTree(json).forEach(value->result.add(value.asString()));return List.copyOf(result);}
    private TeamViews.Person person(long id){var names=jdbc.queryForList("SELECT nickname FROM users WHERE id=?",String.class,id);return new TeamViews.Person(Long.toString(id),names.isEmpty()?null:names.getFirst());}
    private TeamViews.Application application(long id){var rows=jdbc.query("SELECT * FROM team_applications WHERE id=?",(rs,i)->new TeamViews.Application(rs.getString("id"),rs.getString("team_id"),person(rs.getLong("user_id")),rs.getString("open_role_id"),rs.getString("message"),rs.getString("status"),rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getObject("updated_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private TeamRow lockTeam(long id){var rows=jdbc.query("SELECT id,campus_id,leader_id,status,version FROM teams WHERE id=? AND deleted_at IS NULL FOR UPDATE",(rs,i)->new TeamRow(rs.getLong("id"),rs.getLong("campus_id"),rs.getLong("leader_id"),rs.getString("status"),rs.getInt("version")),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private void authorizeLeader(Actor actor,TeamRow team){if(team.campusId()!=actor.campusId()||team.leaderId()!=actor.userId())throw missing();}
    private long resolveRole(long team,JsonNode input){
        var active=jdbc.queryForList("SELECT id FROM team_open_roles WHERE team_id=? AND active=TRUE ORDER BY id FOR UPDATE",Long.class,team);
        if(active.isEmpty())throw repairRequired();
        if(input==null||input.isNull()){if(active.size()!=1)throw invalid();return active.getFirst();}
        long role=IdentityInputs.id(input);if(!active.contains(role))throw invalid();return role;
    }
    private void checkCapacity(TeamRow team,long role){
        int quantity=jdbc.queryForObject("SELECT quantity FROM team_open_roles WHERE id=? AND team_id=? AND active=TRUE FOR UPDATE",Integer.class,role,team.id());
        int occupied=occupied(team.id(),role);
        int total=capacity(team.id());
        int members=recruited(team.id());
        if(occupied>=quantity||members>=total)throw conflict();
    }
    private List<JsonNode> roles(long team){return jdbc.query("SELECT id,role,skill_tags,quantity FROM team_open_roles WHERE team_id=? AND active=TRUE ORDER BY id",(rs,i)->{var node=mapper.createObjectNode();node.put("id",rs.getString("id"));node.put("role",rs.getString("role"));node.set("skillTags",mapper.valueToTree(tags(rs.getString("skill_tags"))));node.put("quantity",rs.getInt("quantity"));return (JsonNode)node;},team);}
    private void replaceRoles(long team,JsonNode input){
        var requested=TeamInputs.checkOpenRoles(input);Set<Long> kept=new HashSet<>();
        var existing=jdbc.queryForList("SELECT id FROM team_open_roles WHERE team_id=? AND active=TRUE ORDER BY id FOR UPDATE",Long.class,team);
        int capacity=0;
        for(var node:requested){
            long role=node.has("id")?IdentityInputs.id(node.get("id")):ids.nextId();if(!kept.add(role))throw invalid();
            int quantity=node.path("quantity").asInt();capacity+=quantity;
            String name=text(node.get("role"),64,false),skills=mapper.writeValueAsString(node.hasNonNull("skillTags")?node.get("skillTags"):mapper.createArrayNode());
            if(node.has("id")){
                if(!existing.contains(role))throw invalid();
                if(occupied(team,role)>quantity)throw conflict();
                jdbc.update("UPDATE team_open_roles SET role=?,skill_tags=?,quantity=? WHERE id=? AND team_id=?",name,skills,quantity,role,team);
            }else jdbc.update("INSERT INTO team_open_roles(id,team_id,role,skill_tags,quantity,active,created_at) VALUES(?,?,?,?,?,TRUE,?)",role,team,name,skills,quantity,now());
        }
        for(long role:existing)if(!kept.contains(role)){
            if(occupied(team,role)>0)throw conflict();
            jdbc.update("UPDATE team_open_roles SET active=FALSE WHERE id=? AND team_id=?",role,team);
        }
        int members=recruited(team);
        if(capacity<members)throw conflict();
    }
    // Locking reads see current committed rows even after authentication established an RR snapshot.
    private int occupied(long team,long role){return jdbc.queryForList("SELECT id FROM team_members WHERE team_id=? AND open_role_id=? AND status='member' FOR UPDATE",Long.class,team,role).size();}
    private int recruited(long team){return jdbc.queryForList("SELECT m.id FROM team_members m JOIN teams t ON t.id=m.team_id WHERE m.team_id=? AND m.status='member' AND m.user_id<>t.leader_id FOR UPDATE",Long.class,team).size();}
    private int capacity(long team){return jdbc.queryForList("SELECT quantity FROM team_open_roles WHERE team_id=? AND active=TRUE ORDER BY id FOR UPDATE",Integer.class,team).stream().mapToInt(Integer::intValue).sum();}
    private void refreshStatus(long team){int remaining=capacity(team)-recruited(team);jdbc.update("UPDATE teams SET status=? WHERE id=? AND status<>'closed'",remaining<=0?"full":"recruiting",team);}
    private BusinessException repairRequired(){return new BusinessException(409,"TEAM_ROLES_REPAIR_REQUIRED","队长需更新岗位；旧申请请先拒绝，再使用岗位ID重新申请");}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private String escape(String text){return text.replace("!","!!").replace("%","!%").replace("_","!_");}
    private BusinessException missing(){return new BusinessException(404,"NOT_FOUND","未找到请求的资源");}
    private BusinessException conflict(){return new BusinessException(409,"VERSION_CONFLICT","信息已更新，请刷新后重试");}
    private BusinessException forbidden(){return new BusinessException(403,"FORBIDDEN","无权执行此操作");}
    private BusinessException invalid(){return new BusinessException(400,"VALIDATION_FAILED","请检查填写的信息");}
    private record ApplyRow(long id,long teamId,long userId,String status,String openRoleId){}
    private record TeamRow(long id,long campusId,long leaderId,String status,int version){}
}
