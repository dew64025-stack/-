package com.caizhitong.mutualaid;

import com.caizhitong.common.*;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.pagination.*;
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
public class MutualAidService {
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    private final AttachmentService files;
    private final com.caizhitong.resource.ServiceSources sources;
    public MutualAidService(JdbcTemplate jdbc,CurrentActor current,SnowflakeIds ids,Clock clock,JsonMapper mapper,CursorCodec cursors,SensitiveDataCrypto crypto,AttachmentService files,com.caizhitong.resource.ServiceSources sources){
        this.jdbc=jdbc;this.current=current;this.ids=ids;this.clock=clock;this.mapper=mapper;this.cursors=cursors;this.crypto=crypto;this.files=files;this.sources=sources;
    }

    @BusinessWrite(student=true) public MutualAidViews.Post create(MutualAidInputs.Create input){
        Actor actor=current.requireStudent();
        long id=ids.nextId();LocalDateTime now=now();
        String detail=input.detail()==null||input.detail().isNull()?null:mapper.writeValueAsString(input.detail());
        String images=imagesJson(actor,MutualAidInputs.checkAttachmentIds(input.attachmentIds()));
        jdbc.update("INSERT INTO mutual_aid_posts(id,campus_id,user_id,category,title,content,detail_json,images_json,price,exchange_area,status,source_type,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,'pending','manual',0,?,?)",
                id,actor.campusId(),actor.userId(),MutualAidInputs.checkCategory(input.category()),text(input.title(),256,false),
                input.content()==null?null:text(input.content(),2048,true),detail,images,MutualAidInputs.checkPrice(input.price()),
                input.exchangeArea()==null?null:text(input.exchangeArea(),128,true),now,now);
        queueModeration(actor.campusId(),"mutual_aid_post",id,now);
        return post(id);
    }

    @BusinessWrite public MutualAidViews.Post update(long id,MutualAidInputs.Update input){
        Actor actor=current.require();authorizeOwner(actor,id);
        int version=version(input.version());int actual=jdbc.queryForObject("SELECT version FROM mutual_aid_posts WHERE id=? FOR UPDATE",Integer.class,id);
        if(actual!=version)throw conflict();
        String category=jdbc.queryForObject("SELECT category FROM mutual_aid_posts WHERE id=?",String.class,id);
        if(input.detail()!=null){if(input.detail().isNull())throw invalid();MutualAidInputs.checkDetail(category,input.detail());}
        if(MutualAidInputs.checkPrice(input.price())!=null&&!Set.of("secondhand","textbook").contains(category))throw invalid();
        String detail=input.detail()==null?null:input.detail().isNull()?null:mapper.writeValueAsString(input.detail());
        String images=input.attachmentIds()==null?null:imagesJson(actor,MutualAidInputs.checkAttachmentIds(input.attachmentIds()));
        jdbc.update("UPDATE mutual_aid_posts SET title=COALESCE(?,title),content=COALESCE(?,content),detail_json=COALESCE(?,detail_json),images_json=COALESCE(?,images_json),price=COALESCE(?,price),exchange_area=COALESCE(?,exchange_area),status='pending',version=version+1,updated_at=? WHERE id=?",
                input.title()==null?null:text(input.title(),256,false),input.content()==null?null:text(input.content(),2048,true),detail,images,
                input.price()==null?null:MutualAidInputs.checkPrice(input.price()),input.exchangeArea()==null?null:text(input.exchangeArea(),128,true),now(),id);
        queueModeration(actor.campusId(),"mutual_aid_post",id,now());
        return post(id);
    }

    @BusinessWrite public MutualAidViews.Post action(long id,MutualAidInputs.Action input){
        Actor actor=current.require();authorizeOwner(actor,id);
        String action=text(input.action(),16,false);int version=version(input.version());
        int actual=jdbc.queryForObject("SELECT version FROM mutual_aid_posts WHERE id=? FOR UPDATE",Integer.class,id);
        if(actual!=version)throw conflict();
        String status=jdbc.queryForObject("SELECT status FROM mutual_aid_posts WHERE id=?",String.class,id);
        String next=switch(action){
            case "reserve"->{if(!status.equals("published"))throw conflict();yield "reserved";}
            case "complete"->{if(!status.equals("reserved"))throw conflict();yield "completed";}
            case "cancel"->{if(!Set.of("published","reserved","communicating").contains(status))throw conflict();yield "cancelled";}
            default->throw invalid();
        };
        if(action.equals("cancel")||action.equals("complete")){
            if(jdbc.queryForObject("SELECT COUNT(*) FROM borrow_requests WHERE post_id=? AND status IN ('pending','confirmed','handed_over')",Integer.class,id)>0)
                throw new BusinessException(409,"BORROW_IN_PROGRESS","存在未完成的借用，无法变更状态");
        }
        jdbc.update("UPDATE mutual_aid_posts SET status=?,version=version+1,updated_at=? WHERE id=?",next,now(),id);
        return post(id);
    }

    @BusinessWrite(student=true) public MutualAidViews.Rating rate(long id,MutualAidInputs.Rating input){
        Actor actor=current.requireStudent();
        PostRow p=postRow(id);if(p.campusId()!=actor.campusId())throw missing();
        if(!participant(actor,p))throw forbidden();
        if(!Set.of("reserved","completed","communicating").contains(p.status()))throw conflict();
        long rating=ids.nextId();LocalDateTime now=now();
        int score=input.score().asInt();String comment=input.comment()==null?null:text(input.comment(),512,true);
        jdbc.update("INSERT INTO mutual_aid_ratings(id,post_id,rater_id,score,comment,created_at) VALUES(?,?,?,?,?,?)",rating,id,actor.userId(),score,comment,now);
        return new MutualAidViews.Rating(Long.toString(rating),score,comment,now.toInstant(ZoneOffset.UTC));
    }

    @BusinessWrite(student=true) public MutualAidViews.Borrow borrow(long postId,MutualAidInputs.BorrowStart input){
        Actor actor=current.requireStudent();
        PostRow p=postRow(postId);if(p.campusId()!=actor.campusId())throw missing();
        if(!p.category().equals("borrow"))throw conflict();
        if(p.userId()==actor.userId())throw conflict();
        if(!p.status().equals("published"))throw conflict();
        if(!detailHas(p,"kind","offer"))throw conflict();
        long id=ids.nextId();LocalDateTime now=now();
        Instant start=MutualAidInputs.instant(input.startTime()),end=MutualAidInputs.instant(input.endTime());
        jdbc.update("INSERT INTO borrow_requests(id,post_id,borrower_id,start_time,end_time,status,created_at,updated_at) VALUES(?,?,?,?,?,'pending',?,?)",
                id,postId,actor.userId(),start==null?null:LocalDateTime.ofInstant(start,ZoneOffset.UTC),end==null?null:LocalDateTime.ofInstant(end,ZoneOffset.UTC),now,now);
        return borrow(id);
    }

    @BusinessWrite public MutualAidViews.Borrow borrowAction(long id,MutualAidInputs.BorrowAction input){
        Actor actor=current.require();String action=text(input.action(),24,false);
        var r=jdbc.query("SELECT b.id,b.post_id,b.borrower_id,b.status,p.user_id AS owner_id,p.campus_id FROM borrow_requests b JOIN mutual_aid_posts p ON p.id=b.post_id WHERE b.id=? FOR UPDATE",
                (rs,i)->new BorrowRow(rs.getLong("id"),rs.getLong("post_id"),rs.getLong("borrower_id"),rs.getLong("owner_id"),rs.getLong("campus_id"),rs.getString("status")),id);
        if(r.isEmpty())throw missing();BorrowRow row=r.getFirst();
        if(row.campusId()!=actor.campusId())throw missing();
        boolean owner=actor.userId()==row.ownerId(),borrower=actor.userId()==row.borrowerId();
        if(!owner&&!borrower)throw forbidden();
        LocalDateTime now=now();
        String next=switch(action){
            case "approve"->{if(!owner||!row.status().equals("pending"))throw conflict();yield "confirmed";}
            case "cancel"->{if(!Set.of("pending","confirmed").contains(row.status()))throw conflict();yield "cancelled";}
            case "confirm_handover"->{if(!row.status().equals("confirmed"))throw conflict();yield confirmedBoth(id,row,actor.userId(),"confirm_handover",now)?"handed_over":row.status();}
            case "confirm_return"->{if(!row.status().equals("handed_over"))throw conflict();yield confirmedBoth(id,row,actor.userId(),"confirm_return",now)?"returned":row.status();}
            default->throw invalid();
        };
        if(!next.equals(row.status()))jdbc.update("UPDATE borrow_requests SET status=?,updated_at=? WHERE id=?",next,now,id);
        return borrow(id);
    }

    private boolean confirmedBoth(long request,BorrowRow row,long actorId,String action,LocalDateTime now){
        event(request,actorId,action,now);
        Set<Long> actors=new HashSet<>();
        jdbc.queryForList("SELECT actor_id FROM borrow_events WHERE borrow_request_id=? AND action=? FOR SHARE",Long.class,request,action).forEach(actors::add);
        return actors.contains(row.ownerId())&&actors.contains(row.borrowerId());
    }
    private void event(long request,long actorId,String action,LocalDateTime now){jdbc.update("INSERT INTO borrow_events(id,borrow_request_id,actor_id,action,created_at) VALUES(?,?,?,?,?)",ids.nextId(),request,actorId,action,now);}

    @BusinessWrite public MutualAidViews.Match matchAction(long id,MutualAidInputs.MatchAction input){
        Actor actor=current.require();String action=text(input.action(),24,false);
        var m=jdbc.query("SELECT lost_post_id,found_post_id,status FROM lost_found_matches WHERE id=? FOR UPDATE",(rs,i)->new MatchRow(rs.getLong("lost_post_id"),rs.getLong("found_post_id"),rs.getString("status")),id);
        if(m.isEmpty())throw missing();long lost=m.getFirst().lost(),found=m.getFirst().found();
        PostRow lostPost=postRow(lost),foundPost=postRow(found);
        if(lostPost.campusId()!=actor.campusId()||foundPost.campusId()!=actor.campusId())throw missing();
        if(!(ownsPost(actor,lost)||ownsPost(actor,found)))throw forbidden();
        if(lostPost.userId()==foundPost.userId())throw new BusinessException(409,"DISTINCT_PARTIES_REQUIRED","失物归还需要两位不同参与人");
        String status=m.getFirst().status();
        boolean recorded=!jdbc.queryForList("SELECT id FROM lost_found_events WHERE match_id=? AND actor_id=? AND action=? FOR SHARE",Long.class,id,actor.userId(),action).isEmpty();
        if(recorded)return match(id,true);
        if(action.equals("verify")&&!Set.of("suggested","verified").contains(status))throw conflict();
        if(action.equals("confirm_return")&&!status.equals("verified"))throw conflict();
        if(action.equals("ignore")&&!Set.of("suggested","verified").contains(status))throw conflict();
        jdbc.update("INSERT INTO lost_found_events(id,match_id,actor_id,action,description,created_at) VALUES(?,?,?,?,?,?)",ids.nextId(),id,actor.userId(),action,input.description()==null?null:text(input.description(),512,true),now());
        String next=switch(action){
            case "verify"->"verified";
            case "confirm_return"->{var parties=jdbc.queryForList("SELECT actor_id FROM lost_found_events WHERE match_id=? AND action='confirm_return' FOR SHARE",Long.class,id);yield parties.contains(lostPost.userId())&&parties.contains(foundPost.userId())?"returned":"verified";}
            case "ignore"->"ignored";
            default->throw invalid();
        };
        jdbc.update("UPDATE lost_found_matches SET status=? WHERE id=?",next,id);
        return match(id,true);
    }

    // ---- reads ----
    public MutualAidViews.Page list(long campus,String q,String category,String cursor,Integer limit){
        int size=PageLimits.resolve(limit);q=q==null?"":q.trim();
        String scope="mutual-aid:"+campus+":"+category+":"+HexFormat.of().formatHex(crypto.searchHash("mutual-aid-list",q));
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM mutual_aid_posts WHERE campus_id=? AND status='published' AND deleted_at IS NULL AND id<?";
        List<Object> args=new ArrayList<>(List.of(campus,before));
        if(category!=null){sql+=" AND category=?";args.add(category);}
        if(!q.isEmpty()){sql+=" AND (title LIKE ? ESCAPE '!' OR content LIKE ? ESCAPE '!')";args.add("%"+escape(q)+"%");args.add("%"+escape(q)+"%");}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::post).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new MutualAidViews.Page(page,next,more);
    }

    public MutualAidViews.Detail detail(long id,long campus){
        Actor actor=current.optional().orElse(null);PostRow p=visiblePost(id,campus,actor);
        var u=jdbc.query("SELECT id,nickname FROM users WHERE id=?",(rs,i)->new MutualAidViews.Author(Long.toString(rs.getLong("id")),rs.getString("nickname")),p.userId());
        MutualAidViews.Author author=u.isEmpty()?new MutualAidViews.Author(Long.toString(p.userId()),null):u.getFirst();
        boolean canEdit=actor!=null&&actor.userId()==p.userId()&&!Set.of("deleted").contains(p.status());
        boolean canContact=actor!=null&&actor.userId()!=p.userId()&&p.status().equals("published");
        return new MutualAidViews.Detail(postView(p),author,canContact,canEdit);
    }

    public MutualAidViews.RatingPage ratings(long id,long campus,String cursor,Integer limit){
        visiblePost(id,campus,current.optional().orElse(null));
        int size=PageLimits.resolve(limit);
        String scope="mutual-aid-ratings:"+id;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        var found=jdbc.query("SELECT id,score,comment,created_at FROM mutual_aid_ratings WHERE post_id=? AND id<? ORDER BY id DESC LIMIT ?",
                (rs,i)->new MutualAidViews.Rating(Long.toString(rs.getLong("id")),rs.getInt("score"),rs.getString("comment"),rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id,before,size+1);
        boolean more=found.size()>size;var page=found.stream().limit(size).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new MutualAidViews.RatingPage(page,next,more);
    }

    public MutualAidViews.Page myPosts(String category,String status,String cursor,Integer limit){
        Actor actor=current.require();int size=PageLimits.resolve(limit);
        String scope="mutual-aid-mine:"+actor.userId()+":"+category+":"+status;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM mutual_aid_posts WHERE user_id=? AND campus_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.userId(),actor.campusId(),before));
        if(category!=null){sql+=" AND category=?";args.add(category);}
        if(status!=null){sql+=" AND status=?";args.add(status);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::post).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new MutualAidViews.Page(page,next,more);
    }

    public MutualAidViews.MatchPage matches(String postId,String cursor,Integer limit){
        Actor actor=current.requireStudent();int size=PageLimits.resolve(limit);
        String scope="lost-found:"+actor.userId();
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM lost_found_matches WHERE (lost_post_id IN (SELECT id FROM mutual_aid_posts WHERE user_id=?) OR found_post_id IN (SELECT id FROM mutual_aid_posts WHERE user_id=?)) AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.userId(),actor.userId(),before));
        if(postId!=null){sql+=" AND (lost_post_id=? OR found_post_id=?)";args.add(id(postId));args.add(id(postId));}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::match).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new MutualAidViews.MatchPage(page,next,more);
    }

    public MutualAidViews.BorrowPage myBorrows(String side,String status,String cursor,Integer limit){
        if(side!=null&&!Set.of("borrower","owner").contains(side))throw invalid();
        Actor actor=current.require();int size=PageLimits.resolve(limit);
        String scope="borrows:"+actor.userId()+":"+side+":"+status;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="owner".equals(side)
                ?"SELECT b.id FROM borrow_requests b JOIN mutual_aid_posts p ON p.id=b.post_id WHERE p.user_id=? AND b.id<?"
                :"SELECT b.id FROM borrow_requests b WHERE b.borrower_id=? AND b.id<?";
        List<Object> args=new ArrayList<>(List.of(actor.userId(),before));
        if(status!=null){sql+=" AND b.status=?";args.add(status);}
        sql+=" ORDER BY b.id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::borrow).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new MutualAidViews.BorrowPage(page,next,more);
    }

    // ---- helpers ----
    private PostRow visiblePost(long id,long campus,Actor actor){
        PostRow p=postRow(id);
        if(p.campusId()!=campus||"deleted".equals(p.status())||jdbc.queryForObject("SELECT COUNT(*) FROM mutual_aid_posts WHERE id=? AND deleted_at IS NOT NULL",Integer.class,id)>0)throw missing();
        if(!"published".equals(p.status())&&(actor==null||actor.campusId()!=campus||actor.userId()!=p.userId()))throw missing();
        return p;
    }
    private JsonNode publicDetail(String detail){
        if(detail==null)return null;
        JsonNode result=mapper.readTree(detail);
        removePrivateProof(result);
        return result;
    }
    private void removePrivateProof(JsonNode node){
        if(node instanceof tools.jackson.databind.node.ObjectNode object)object.remove("privateProofHint");
        if(node.isContainer())for(JsonNode child:node)removePrivateProof(child);
    }
    private void authorizeOwner(Actor actor,long id){if(jdbc.queryForObject("SELECT COUNT(*) FROM mutual_aid_posts WHERE id=? AND campus_id=? AND user_id=?",Integer.class,id,actor.campusId(),actor.userId())!=1)throw missing();}
    private boolean ownsPost(Actor actor,long id){return jdbc.queryForObject("SELECT COUNT(*) FROM mutual_aid_posts WHERE id=? AND user_id=? AND campus_id=?",Integer.class,id,actor.userId(),actor.campusId())==1;}
    private boolean participant(Actor actor,PostRow p){return actor.userId()==p.userId()||jdbc.queryForObject("SELECT COUNT(*) FROM borrow_requests WHERE post_id=? AND borrower_id=? AND status IN ('confirmed','handed_over','returned')",Integer.class,p.id(),actor.userId())>0;}
    private void queueModeration(long campus,String type,long target,LocalDateTime now){jdbc.update("INSERT INTO moderation_tasks(id,campus_id,target_type,target_id,status,created_at,updated_at) VALUES(?,?,?,?,'pending',?,?)",ids.nextId(),campus,type,target,now,now);}
    private String imagesJson(Actor actor,List<Long> attachments){for(long a:attachments)files.owner(actor,a,"mutual_aid",true,false);return attachments.isEmpty()?null:mapper.writeValueAsString(attachments);}
    private boolean detailHas(PostRow p,String key,String value){if(p.detail()==null)return false;JsonNode n=mapper.readTree(p.detail());JsonNode v=n.get(key);return v!=null&&v.isString()&&v.asString().equals(value);}
    private MutualAidViews.Post post(long id){return postView(postRow(id));}
    private PostRow postRow(long id){var rows=jdbc.query("SELECT * FROM mutual_aid_posts WHERE id=?",(rs,i)->new PostRow(rs.getLong("id"),rs.getLong("campus_id"),rs.getLong("user_id"),rs.getString("category"),rs.getString("title"),rs.getString("content"),rs.getString("detail_json"),rs.getString("images_json"),rs.getString("price"),rs.getString("exchange_area"),rs.getString("status"),rs.getInt("version"),rs.getObject("created_at",LocalDateTime.class),rs.getString("source_type"),rs.getObject("updated_at",LocalDateTime.class)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private MutualAidViews.Post postView(PostRow p){
        var images=com.caizhitong.resource.ServiceSources.attachments(p.images()==null?null:mapper.readTree(p.images()),files,current.optional().orElse(null),p.campusId(),"mutual_aid",p.id());
        var metadata=sources.read(p.campusId(),"mutual_aid_post",p.id(),p.sourceType(),p.updatedAt().toInstant(ZoneOffset.UTC));
        return new MutualAidViews.Post(Long.toString(p.id()),p.category(),p.title(),p.content(),publicDetail(p.detail()),images,p.price(),p.exchangeArea(),p.status(),p.version(),p.createdAt().toInstant(ZoneOffset.UTC),metadata.sourceType(),metadata.sourceName(),metadata.updatedAt(),metadata.isStale(),metadata.officialReference(),metadata.externalId());
    }
    private MutualAidViews.Borrow borrow(long id){var rows=jdbc.query("SELECT b.* FROM borrow_requests b WHERE b.id=?",(rs,i)->new MutualAidViews.Borrow(Long.toString(rs.getLong("id")),Long.toString(rs.getLong("post_id")),borrowerName(rs.getLong("borrower_id")),rs.getObject("start_time",LocalDateTime.class)==null?null:rs.getObject("start_time",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getObject("end_time",LocalDateTime.class)==null?null:rs.getObject("end_time",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getString("status"),rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getObject("updated_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private String borrowerName(long userId){var rows=jdbc.queryForList("SELECT nickname FROM users WHERE id=?",String.class,userId);return rows.isEmpty()?null:rows.getFirst();}
    private MutualAidViews.Match match(long id){return match(id,false);}
    private MutualAidViews.Match match(long id,boolean currentRead){var rows=jdbc.query("SELECT id,lost_post_id,found_post_id,similarity,status FROM lost_found_matches WHERE id=?"+(currentRead?" FOR UPDATE":""),(rs,i)->new MutualAidViews.Match(Long.toString(rs.getLong("id")),Long.toString(rs.getLong("lost_post_id")),Long.toString(rs.getLong("found_post_id")),rs.getBigDecimal("similarity")==null?null:rs.getBigDecimal("similarity").toPlainString(),rs.getString("status"),confirmation(id)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private String confirmation(long id){int count=new HashSet<>(jdbc.queryForList("SELECT e.actor_id FROM lost_found_events e JOIN lost_found_matches m ON m.id=e.match_id JOIN mutual_aid_posts l ON l.id=m.lost_post_id JOIN mutual_aid_posts f ON f.id=m.found_post_id WHERE e.match_id=? AND e.action='confirm_return' AND e.actor_id IN (l.user_id,f.user_id) FOR SHARE",Long.class,id)).size();return count==0?"unconfirmed":count==1?"partially_confirmed":"confirmed";}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private String escape(String text){return text.replace("!","!!").replace("%","!%").replace("_","!_");}
    private BusinessException missing(){return new BusinessException(404,"NOT_FOUND","未找到请求的资源");}
    private BusinessException conflict(){return new BusinessException(409,"VERSION_CONFLICT","信息已更新，请刷新后重试");}
    private BusinessException forbidden(){return new BusinessException(403,"FORBIDDEN","无权执行此操作");}
    private BusinessException invalid(){return new BusinessException(400,"VALIDATION_FAILED","请检查填写的信息");}
    private record PostRow(long id,long campusId,long userId,String category,String title,String content,String detail,String images,String price,String exchangeArea,String status,int version,LocalDateTime createdAt,String sourceType,LocalDateTime updatedAt){}
    private record BorrowRow(long id,long postId,long borrowerId,long ownerId,long campusId,String status){}
    private record MatchRow(long lost,long found,String status){}
}
