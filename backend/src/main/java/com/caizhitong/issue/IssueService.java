package com.caizhitong.issue;

import com.caizhitong.common.*;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.pagination.*;
import com.caizhitong.identity.IdentityInputs;
import com.caizhitong.material.files.AttachmentService;
import com.caizhitong.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.*;

@Service
public class IssueService {
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    private final AttachmentService files;
    public IssueService(JdbcTemplate jdbc,CurrentActor current,SnowflakeIds ids,Clock clock,JsonMapper mapper,CursorCodec cursors,SensitiveDataCrypto crypto,AttachmentService files){
        this.jdbc=jdbc;this.current=current;this.ids=ids;this.clock=clock;this.mapper=mapper;this.cursors=cursors;this.crypto=crypto;this.files=files;
    }

    @BusinessWrite(student=true) public IssueViews.Issue create(IssueInputs.Create input){
        Actor actor=current.requireStudent();
        long id=ids.nextId();LocalDateTime now=now();
        jdbc.update("INSERT INTO campus_issues(id,campus_id,user_id,title,category,description,status,follow_count,vote_count,source_type,version,created_at,updated_at,visibility) VALUES(?,?,?,?,?,?,'pending',0,0,'manual',0,?,?,'private')",
                id,actor.campusId(),actor.userId(),text(input.title(),256,false),text(input.category(),32,false),input.description()==null?null:text(input.description(),1024,true),now,now);
        if(input.location()!=null&&!input.location().isNull()){
            JsonNode loc=input.location();
            jdbc.update("INSERT INTO campus_issue_locations(id,issue_id,name,latitude,longitude,created_at) VALUES(?,?,?,?,?,?)",
                    ids.nextId(),id,loc.hasNonNull("name")?loc.get("name").asString():null,loc.get("latitude").asDouble(),loc.get("longitude").asDouble(),now);
        }
        for(long a:IssueInputs.checkAttachmentIds(input.attachmentIds()))files.owner(actor,a,"issue",true,false);
        return issue(id);
    }

    @BusinessWrite public IssueViews.Follow follow(long id){
        Actor actor=current.require();
        checkIssue(id,actor.campusId());
        long fid=ids.nextId();LocalDateTime now=now();
        jdbc.update("INSERT INTO campus_issue_follows(id,issue_id,user_id,created_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE id=id",fid,id,actor.userId(),now);
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM campus_issue_follows WHERE issue_id=?",Integer.class,id);
        jdbc.update("UPDATE campus_issues SET follow_count=? WHERE id=?",count,id);
        return new IssueViews.Follow(true,count);
    }

    @BusinessWrite public IssueViews.Follow unfollow(long id){
        Actor actor=current.require();
        checkIssue(id,actor.campusId());
        jdbc.update("DELETE FROM campus_issue_follows WHERE issue_id=? AND user_id=?",id,actor.userId());
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM campus_issue_follows WHERE issue_id=?",Integer.class,id);
        jdbc.update("UPDATE campus_issues SET follow_count=? WHERE id=?",count,id);
        return new IssueViews.Follow(false,count);
    }

    @BusinessWrite(student=true) public IssueViews.Vote vote(long id,IssueInputs.Vote input){
        Actor actor=current.requireStudent();
        checkIssue(id,actor.campusId());
        int value=input.value().asInt();
        jdbc.update("INSERT INTO campus_issue_votes(id,issue_id,user_id,value,created_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE value=?",ids.nextId(),id,actor.userId(),value,now(),value);
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM campus_issue_votes WHERE issue_id=?",Integer.class,id);
        jdbc.update("UPDATE campus_issues SET vote_count=? WHERE id=?",count,id);
        return new IssueViews.Vote(value,count);
    }

    @BusinessWrite(student=true) public IssueViews.Vote unvote(long id){
        Actor actor=current.requireStudent();
        checkIssue(id,actor.campusId());
        jdbc.update("DELETE FROM campus_issue_votes WHERE issue_id=? AND user_id=?",id,actor.userId());
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM campus_issue_votes WHERE issue_id=?",Integer.class,id);
        jdbc.update("UPDATE campus_issues SET vote_count=? WHERE id=?",count,id);
        return new IssueViews.Vote(null,count);
    }

    @BusinessWrite(student=true) public IssueViews.Evidence evidence(long id,IssueInputs.Evidence input){
        Actor actor=current.requireStudent();
        checkIssue(id,actor.campusId());
        for(long a:IssueInputs.checkAttachmentIds(input.attachmentIds()))files.owner(actor,a,"issue",true,false);
        long eid=ids.nextId();
        jdbc.update("INSERT INTO campus_issue_evidences(id,issue_id,user_id,content,images_json,created_at) VALUES(?,?,?,?,?,?)",
                eid,id,actor.userId(),input.content()==null||input.content().isNull()?null:text(input.content(),512,false),input.attachmentIds()==null||input.attachmentIds().isNull()?null:mapper.writeValueAsString(IssueInputs.checkAttachmentIds(input.attachmentIds())),now());
        return new IssueViews.Evidence(Long.toString(eid),"pending");
    }

    @BusinessWrite(permission="issue.review") public IssueViews.Issue decide(long id,IssueInputs.Decision input){
        Actor actor=current.requirePermission("issue.review");
        String decision=text(input.decision(),16,false);int version=IdentityInputs.version(input.version());
        int actual=jdbc.queryForObject("SELECT version FROM campus_issues WHERE id=? AND campus_id=? FOR UPDATE",Integer.class,id,actor.campusId());
        if(actual!=version)throw conflict();
        String visibility=switch(decision){case "publish"->"public";case "private"->"private";case "reject"->"private";default->throw invalid();};
        String status=switch(decision){case "publish"->"published";case "private"->"hidden";case "reject"->"rejected";default->throw invalid();};
        jdbc.update("UPDATE campus_issues SET status=?,visibility=?,version=version+1,updated_at=? WHERE id=?",status,visibility,now(),id);
        return issue(id);
    }

    @BusinessWrite(permission="issue.process") public IssueViews.Issue transition(long id,IssueInputs.Transition input){
        Actor actor=current.requirePermission("issue.process");
        String to=text(input.toStatus(),16,false);int version=IdentityInputs.version(input.version());
        int actual=jdbc.queryForObject("SELECT version FROM campus_issues WHERE id=? AND campus_id=? FOR UPDATE",Integer.class,id,actor.campusId());
        if(actual!=version)throw conflict();
        jdbc.update("UPDATE campus_issues SET status=?,version=version+1,updated_at=? WHERE id=?",to,now(),id);
        return issue(id);
    }

    @BusinessWrite(student=true) public IssueViews.Crowd observe(IssueInputs.Crowd input){
        Actor actor=current.requireStudent();
        long id=ids.nextId();LocalDateTime now=now();
        jdbc.update("INSERT INTO crowd_observations(id,campus_id,location_type,location_name,crowd_level,source_type,observed_at,is_stale,created_at) VALUES(?,?,?,?,?,'manual',?,0,?)",
                id,actor.campusId(),text(input.locationType(),32,false),text(input.locationName(),128,false),text(input.crowdLevel(),16,false),now,now);
        return crowd(id);
    }

    public IssueViews.Page list(long campus,String q,String category,String cursor,Integer limit){
        int size=PageLimits.resolve(limit);q=q==null?"":q.trim();
        String scope="issues:"+campus+":"+category+":"+HexFormat.of().formatHex(crypto.searchHash("issue-list",q));
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM campus_issues WHERE campus_id=? AND visibility='public' AND deleted_at IS NULL AND id<?";
        List<Object> args=new ArrayList<>(List.of(campus,before));
        if(category!=null){sql+=" AND category=?";args.add(category);}
        if(!q.isEmpty()){sql+=" AND title LIKE ? ESCAPE '!'";args.add("%"+escape(q)+"%");}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::issue).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new IssueViews.Page(page,next,more);
    }

    public IssueViews.Issue detail(long id,long campus){
        Actor actor=current.optional().orElse(null);
        var row=jdbc.queryForList("SELECT user_id,campus_id,visibility FROM campus_issues WHERE id=?",id);
        if(row.isEmpty())throw missing();
        if(!((Long)row.getFirst().get("campus_id")==campus))throw missing();
        boolean owner=actor!=null&&(Long)row.getFirst().get("user_id")==actor.userId();
        boolean visible="public".equals(row.getFirst().get("visibility"));
        if(!visible&&!owner)throw missing();
        return issue(id);
    }

    public IssueViews.Map map(long campus,String bbox,String category,Integer limit){
        int size=limit==null?200:Math.min(limit,200);
        List<Object> args=new ArrayList<>(List.of(campus));
        String sql="SELECT i.id,i.title,i.category,i.status,l.latitude,l.longitude FROM campus_issues i JOIN campus_issue_locations l ON l.issue_id=i.id WHERE i.campus_id=? AND i.visibility='public' AND i.deleted_at IS NULL";
        if(bbox!=null){String[] b=bbox.split(",");if(b.length!=4)throw invalid();double minLng=Double.parseDouble(b[0]),minLat=Double.parseDouble(b[1]),maxLng=Double.parseDouble(b[2]),maxLat=Double.parseDouble(b[3]);sql+=" AND l.longitude BETWEEN ? AND ? AND l.latitude BETWEEN ? AND ?";args.add(minLng);args.add(maxLng);args.add(minLat);args.add(maxLat);}
        if(category!=null){sql+=" AND i.category=?";args.add(category);}
        sql+=" LIMIT ?";args.add(size+1);
        var rows=jdbc.query(sql,(rs,i)->new IssueViews.Point(Long.toString(rs.getLong("id")),rs.getString("title"),rs.getString("category"),rs.getString("status"),rs.getDouble("latitude"),rs.getDouble("longitude")),args.toArray());
        boolean truncated=rows.size()>size;
        return new IssueViews.Map(rows.stream().limit(size).toList(),truncated);
    }

    public IssueViews.Page myIssues(String status,String cursor,Integer limit){
        Actor actor=current.require();int size=PageLimits.resolve(limit);
        String scope="issues-mine:"+actor.userId()+":"+status;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM campus_issues WHERE user_id=? AND campus_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.userId(),actor.campusId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::issue).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new IssueViews.Page(page,next,more);
    }

    public IssueViews.Page adminIssues(String status,String cursor,Integer limit){
        Actor actor=current.requirePermission("issue.read");int size=PageLimits.resolve(limit);
        String scope="issues-admin:"+actor.campusId()+":"+status;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM campus_issues WHERE campus_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.campusId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::issue).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new IssueViews.Page(page,next,more);
    }

    public IssueViews.CrowdPage crowd(long campus,String locationType,String locationName,String cursor,Integer limit){
        int size=PageLimits.resolve(limit);
        String scope="crowd:"+campus+":"+locationType+":"+locationName;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM crowd_observations WHERE campus_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(campus,before));
        if(locationType!=null){sql+=" AND location_type=?";args.add(locationType);}
        if(locationName!=null){sql+=" AND location_name=?";args.add(locationName);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::crowd).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new IssueViews.CrowdPage(page,next,more);
    }

    private void checkIssue(long id,long campus){if(jdbc.queryForObject("SELECT COUNT(*) FROM campus_issues WHERE id=? AND campus_id=?",Integer.class,id,campus)!=1)throw missing();}
    private IssueViews.Issue issue(long id){var rows=jdbc.query("SELECT id,title,category,description,status,follow_count,vote_count,version,created_at,updated_at FROM campus_issues WHERE id=?",(rs,i)->new IssueViews.Issue(Long.toString(rs.getLong("id")),rs.getString("title"),rs.getString("category"),rs.getString("description"),rs.getString("status"),rs.getInt("follow_count"),rs.getInt("vote_count"),rs.getInt("version"),rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getObject("updated_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private IssueViews.Crowd crowd(long id){var rows=jdbc.query("SELECT id,location_type,location_name,crowd_level,observed_at,is_stale FROM crowd_observations WHERE id=?",(rs,i)->new IssueViews.Crowd(Long.toString(rs.getLong("id")),rs.getString("location_type"),rs.getString("location_name"),rs.getString("crowd_level"),rs.getObject("observed_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getBoolean("is_stale")||rs.getObject("observed_at",LocalDateTime.class).plusMinutes(15).isBefore(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC))),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private String escape(String text){return text.replace("!","!!").replace("%","!%").replace("_","!_");}
    private BusinessException missing(){return new BusinessException(404,"NOT_FOUND","未找到请求的资源");}
    private BusinessException conflict(){return new BusinessException(409,"VERSION_CONFLICT","信息已更新，请刷新后重试");}
    private BusinessException invalid(){return new BusinessException(400,"VALIDATION_FAILED","请检查填写的信息");}
}
