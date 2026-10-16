package com.caizhitong.community;

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
public class CommunityService {
    private static final String ANON = "anonymous-identity";
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    public CommunityService(JdbcTemplate jdbc,CurrentActor current,SnowflakeIds ids,Clock clock,CursorCodec cursors,SensitiveDataCrypto crypto){
        this.jdbc=jdbc;this.current=current;this.ids=ids;this.clock=clock;this.cursors=cursors;this.crypto=crypto;
    }

    @BusinessWrite(student=true) public CommunityViews.Post create(CommunityInputs.Create input){
        Actor actor=current.requireStudent();
        long postId=ids.nextId(),pseudoId=ids.nextId();LocalDateTime now=now();
        String name=randomName();
        jdbc.update("INSERT INTO community_posts(id,campus_id,pseudo_identity_id,author_name,title,content,status,is_high_risk,version,created_at,updated_at) VALUES(?,?,?,?,?,?,'pending',0,0,?,?)",
                postId,actor.campusId(),pseudoId,name,text(input.title(),256,false),text(input.content(),5000,false),now,now);
        insertPseudo(pseudoId,postId,null,actor.userId(),name,now);
        queueModeration(actor.campusId(),"community_post",postId,now);
        return post(postId,true);
    }

    @BusinessWrite public CommunityViews.Post update(long id,CommunityInputs.Update input){
        Actor actor=current.require();authorizePostAuthor(actor,id);
        int actual=jdbc.queryForObject("SELECT version FROM community_posts WHERE id=? FOR UPDATE",Integer.class,id);
        if(actual!=IdentityInputs.version(input.version()))throw conflict();
        jdbc.update("UPDATE community_posts SET title=COALESCE(?,title),content=COALESCE(?,content),status='pending',is_high_risk=0,version=version+1,updated_at=? WHERE id=?",
                input.title()==null?null:text(input.title(),256,false),input.content()==null?null:text(input.content(),5000,false),now(),id);
        queueModeration(actor.campusId(),"community_post",id,now());
        return post(id,true);
    }

    @BusinessWrite public CommunityViews.Deleted delete(long id,int version){
        Actor actor=current.require();authorizePostAuthor(actor,id);
        int actual=jdbc.queryForObject("SELECT version FROM community_posts WHERE id=? FOR UPDATE",Integer.class,id);
        if(actual!=version)throw conflict();
        jdbc.update("UPDATE community_posts SET status='deleted',deleted_at=?,updated_at=?,version=version+1 WHERE id=?",now(),now(),id);
        return new CommunityViews.Deleted(true);
    }

    @BusinessWrite(student=true) public CommunityViews.Comment comment(long postId,CommunityInputs.Comment input){
        Actor actor=current.requireStudent();
        var p=jdbc.queryForList("SELECT campus_id,status FROM community_posts WHERE id=? AND deleted_at IS NULL",postId);
        if(p.isEmpty())throw missing();
        if(((Number)p.getFirst().get("campus_id")).longValue()!=actor.campusId())throw missing();
        if(!p.getFirst().get("status").equals("published"))throw missing();
        long commentId=ids.nextId(),pseudoId=ids.nextId();LocalDateTime now=now();
        String name=randomName();
        jdbc.update("INSERT INTO community_comments(id,post_id,pseudo_identity_id,author_name,content,status,created_at) VALUES(?,?,?,?,?,'pending',?)",
                commentId,postId,pseudoId,name,text(input.content(),2048,false),now);
        insertPseudo(pseudoId,null,commentId,actor.userId(),name,now);
        queueModeration(actor.campusId(),"community_comment",commentId,now);
        return commentView(commentId,true);
    }

    @BusinessWrite public CommunityViews.Deleted deleteComment(long id){
        Actor actor=current.require();authorizeCommentAuthor(actor,id);
        jdbc.update("UPDATE community_comments SET status='deleted' WHERE id=?",id);
        return new CommunityViews.Deleted(true);
    }

    public CommunityViews.Page list(String q,String cursor,Integer limit){
        Actor actor=current.requireStudent();int size=PageLimits.resolve(limit);q=q==null?"":q.trim();
        String scope="community:"+actor.campusId()+":"+HexFormat.of().formatHex(crypto.searchHash("community-list",q));
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM community_posts WHERE campus_id=? AND status='published' AND deleted_at IS NULL AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.campusId(),before));
        if(!q.isEmpty()){sql+=" AND (title LIKE ? ESCAPE '!' OR content LIKE ? ESCAPE '!')";args.add("%"+escape(q)+"%");args.add("%"+escape(q)+"%");}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(id->post(id,false)).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new CommunityViews.Page(page,next,more);
    }

    public CommunityViews.Post detail(long id){
        Actor actor=current.requireStudent();
        boolean author=visiblePost(actor,id);
        return post(id,author);
    }

    private boolean visiblePost(Actor actor,long id){
        var p=jdbc.queryForList("SELECT campus_id,status FROM community_posts WHERE id=? AND deleted_at IS NULL AND status<>'deleted'",id);
        if(p.isEmpty())throw missing();
        if(((Number)p.getFirst().get("campus_id")).longValue()!=actor.campusId())throw missing();
        boolean author=isPostAuthor(actor,id);
        if(!p.getFirst().get("status").equals("published")&&!author)throw missing();
        return author;
    }

    public CommunityViews.CommentPage comments(long postId,String cursor,Integer limit){
        Actor actor=current.requireStudent();
        visiblePost(actor,postId);
        int size=PageLimits.resolve(limit);
        String scope="community-comments:"+postId;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        var found=jdbc.query("SELECT id FROM community_comments WHERE post_id=? AND status='published' AND id<? ORDER BY id DESC LIMIT ?",
                (rs,i)->rs.getLong("id"),postId,before,size+1);
        boolean more=found.size()>size;
        var page=found.stream().limit(size).map(cid->commentView(cid,isCommentAuthor(actor,cid))).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new CommunityViews.CommentPage(page,next,more);
    }

    public CommunityViews.Page myPosts(String status,String cursor,Integer limit){
        Actor actor=current.requireStudent();int size=PageLimits.resolve(limit);
        String scope="community-mine:"+actor.userId()+":"+status;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT p.post_id FROM pseudo_identities p JOIN community_posts c ON c.id=p.post_id WHERE p.user_id_search=? AND p.post_id<?";
        List<Object> args=new ArrayList<>(List.of(searchOf(actor.userId()),before));
        if(status!=null){sql+=" AND c.status=?";args.add(status);}
        sql+=" ORDER BY p.post_id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(id->post(id,true)).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new CommunityViews.Page(page,next,more);
    }

    // ---- helpers ----
    private void insertPseudo(long pseudoId,Long postId,Long commentId,long userId,String name,LocalDateTime now){
        jdbc.update("INSERT INTO pseudo_identities(id,post_id,user_id,random_name,created_at,comment_id,user_id_encrypted,user_id_search) VALUES(?,?,NULL,?,?,?,?,?)",
                pseudoId,postId,name,now,commentId,crypto.encrypt(ANON,Long.toString(userId)),searchOf(userId));
    }
    private String searchOf(long userId){return HexFormat.of().formatHex(crypto.searchHash(ANON,Long.toString(userId)));}
    private boolean isPostAuthor(Actor actor,long postId){return jdbc.queryForObject("SELECT COUNT(*) FROM pseudo_identities WHERE post_id=? AND user_id_search=?",Integer.class,postId,searchOf(actor.userId()))>0;}
    private boolean isCommentAuthor(Actor actor,long commentId){return jdbc.queryForObject("SELECT COUNT(*) FROM pseudo_identities WHERE comment_id=? AND user_id_search=?",Integer.class,commentId,searchOf(actor.userId()))>0;}
    private void authorizePostAuthor(Actor actor,long postId){if(!isPostAuthor(actor,postId))throw missing();}
    private void authorizeCommentAuthor(Actor actor,long commentId){if(!isCommentAuthor(actor,commentId))throw missing();}
    private void queueModeration(long campus,String type,long target,LocalDateTime now){jdbc.update("INSERT INTO moderation_tasks(id,campus_id,target_type,target_id,status,created_at,updated_at) VALUES(?,?,?,?,'pending',?,?)",ids.nextId(),campus,type,target,now,now);}
    private String randomName(){return "匿名"+UUID.randomUUID().toString().replace("-","").substring(0,6);}
    private CommunityViews.Post post(long id,boolean canEdit){var rows=jdbc.query("SELECT id,author_name,title,content,status,version,created_at FROM community_posts WHERE id=?",(rs,i)->new CommunityViews.Post(Long.toString(rs.getLong("id")),rs.getString("title"),rs.getString("content"),rs.getString("author_name"),rs.getString("status"),rs.getInt("version"),canEdit,rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private CommunityViews.Comment commentView(long id,boolean canDelete){var rows=jdbc.query("SELECT id,post_id,author_name,content,status,created_at FROM community_comments WHERE id=?",(rs,i)->new CommunityViews.Comment(Long.toString(rs.getLong("id")),Long.toString(rs.getLong("post_id")),rs.getString("author_name"),rs.getString("content"),rs.getString("status"),canDelete,rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private String escape(String text){return text.replace("!","!!").replace("%","!%").replace("_","!_");}
    private BusinessException missing(){return new BusinessException(404,"NOT_FOUND","未找到请求的资源");}
    private BusinessException conflict(){return new BusinessException(409,"VERSION_CONFLICT","信息已更新，请刷新后重试");}
    private BusinessException invalid(){return new BusinessException(400,"VALIDATION_FAILED","请检查填写的信息");}
}
