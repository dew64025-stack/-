package com.caizhitong.workorder;

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
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.*;

@Service
public class WorkOrderService {
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    private final AttachmentService files;
    public WorkOrderService(JdbcTemplate jdbc,CurrentActor current,SnowflakeIds ids,Clock clock,JsonMapper mapper,CursorCodec cursors,SensitiveDataCrypto crypto,AttachmentService files){
        this.jdbc=jdbc;this.current=current;this.ids=ids;this.clock=clock;this.mapper=mapper;this.cursors=cursors;this.crypto=crypto;this.files=files;
    }

    @BusinessWrite(student=true) public WorkOrderViews.WorkOrder create(WorkOrderInputs.Create input){
        Actor actor=current.requireStudent();
        long id=ids.nextId();LocalDateTime now=now();
        String images=imagesJson(actor,WorkOrderInputs.checkAttachmentIds(input.attachmentIds()));
        jdbc.update("INSERT INTO work_orders(id,campus_id,user_id,category,location,description,urgency,status,images_json,source_type,version,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'pending',?,'manual',0,?,?,?)",
                id,actor.campusId(),actor.userId(),text(input.category(),32,false),input.location()==null?null:text(input.location(),255,true),
                text(input.description(),1024,false),text(input.urgency(),16,false),images,actor.userId(),now,now);
        jdbc.update("INSERT INTO work_order_events(id,work_order_id,from_status,to_status,operator_id,comment,created_at) VALUES(?,?,NULL,'pending',?,NULL,?)",ids.nextId(),id,actor.userId(),now);
        return order(id);
    }

    @BusinessWrite public WorkOrderViews.Rating rate(long id,WorkOrderInputs.Rating input){
        Actor actor=current.require();authorizeOwner(actor,id);
        String status=jdbc.queryForObject("SELECT status FROM work_orders WHERE id=? FOR UPDATE",String.class,id);
        if(!status.equals("completed"))throw conflict();
        long rating=ids.nextId();LocalDateTime now=now();
        int score=input.score().asInt();String comment=input.comment()==null?null:text(input.comment(),512,true);
        jdbc.update("INSERT INTO work_order_ratings(id,work_order_id,user_id,score,comment,created_at) VALUES(?,?,?,?,?,?)",rating,id,actor.userId(),score,comment,now);
        jdbc.update("UPDATE work_orders SET status='rated',version=version+1,updated_at=? WHERE id=?",now,id);
        return new WorkOrderViews.Rating(Long.toString(rating),score,comment,now.toInstant(ZoneOffset.UTC));
    }

    @BusinessWrite(permission="workorder.assign") public WorkOrderViews.WorkOrder assign(long id,WorkOrderInputs.Assign input){
        Actor actor=current.requirePermission("workorder.assign");
        long assignee=IdentityInputs.id(input.assigneeId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND campus_id=?",Integer.class,assignee,actor.campusId())!=1)throw missing();
        int version=IdentityInputs.version(input.version());
        int actual=jdbc.queryForObject("SELECT version FROM work_orders WHERE id=? AND campus_id=? FOR UPDATE",Integer.class,id,actor.campusId());
        if(actual!=version)throw conflict();
        jdbc.update("UPDATE work_orders SET assignee_id=?,version=version+1,updated_at=?,updated_by=? WHERE id=?",assignee,now(),actor.userId(),id);
        return order(id);
    }

    @BusinessWrite(permission="workorder.process") public WorkOrderViews.WorkOrder transition(long id,WorkOrderInputs.Transition input){
        Actor actor=current.requirePermission("workorder.process");
        String to=text(input.toStatus(),16,false);int version=IdentityInputs.version(input.version());
        var row=jdbc.queryForList("SELECT version,status FROM work_orders WHERE id=? AND campus_id=? FOR UPDATE",id,actor.campusId());
        if(row.isEmpty())throw missing();
        int actual=(Integer)row.getFirst().get("version");
        if(actual!=version)throw conflict();
        String from=(String)row.getFirst().get("status");
        String next=switch(to){
            case "accepted"->{if(!Set.of("pending","reviewing").contains(from))throw conflict();yield "accepted";}
            case "processing"->{if(!from.equals("accepted"))throw conflict();yield "processing";}
            case "completed"->{if(!from.equals("processing"))throw conflict();yield "completed";}
            case "rejected"->{if(!Set.of("pending","reviewing").contains(from))throw conflict();yield "rejected";}
            default->throw invalid();
        };
        jdbc.update("UPDATE work_orders SET status=?,version=version+1,updated_at=?,updated_by=? WHERE id=?",next,now(),actor.userId(),id);
        jdbc.update("INSERT INTO work_order_events(id,work_order_id,from_status,to_status,operator_id,comment,created_at) VALUES(?,?,?,?,?,?,?)",ids.nextId(),id,from,next,actor.userId(),input.comment()==null?null:text(input.comment(),512,true),now());
        return order(id);
    }

    public WorkOrderViews.Page myOrders(String status,String cursor,Integer limit){
        Actor actor=current.require();int size=PageLimits.resolve(limit);
        String scope="work-orders-mine:"+actor.userId()+":"+status;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM work_orders WHERE user_id=? AND campus_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.userId(),actor.campusId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::order).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new WorkOrderViews.Page(page,next,more);
    }

    public WorkOrderViews.Detail detail(long id){
        Actor actor=current.require();
        var rows=jdbc.queryForList("SELECT user_id,campus_id FROM work_orders WHERE id=?",id);
        if(rows.isEmpty())throw missing();
        boolean owner=(Long)rows.getFirst().get("user_id")==actor.userId()&&(Long)rows.getFirst().get("campus_id")==actor.campusId();
        boolean admin=actor.kind()==SessionKind.ADMIN&&actor.permissions().contains("workorder.read")&&(Long)rows.getFirst().get("campus_id")==actor.campusId();
        if(!owner&&!admin)throw missing();
        var events=jdbc.query("SELECT id,from_status,to_status,comment,created_at FROM work_order_events WHERE work_order_id=? ORDER BY id ASC",
                (rs,i)->new WorkOrderViews.Event(Long.toString(rs.getLong("id")),rs.getString("from_status"),rs.getString("to_status"),rs.getString("comment"),rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);
        return new WorkOrderViews.Detail(order(id),events);
    }

    public WorkOrderViews.Page adminOrders(String status,String assigneeId,String cursor,Integer limit){
        Actor actor=current.requirePermission("workorder.read");int size=PageLimits.resolve(limit);
        String scope="work-orders:"+actor.campusId()+":"+status+":"+assigneeId;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM work_orders WHERE campus_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.campusId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        if(assigneeId!=null){sql+=" AND assignee_id=?";args.add(id(assigneeId));}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::order).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new WorkOrderViews.Page(page,next,more);
    }

    private void authorizeOwner(Actor actor,long id){if(jdbc.queryForObject("SELECT COUNT(*) FROM work_orders WHERE id=? AND campus_id=? AND user_id=?",Integer.class,id,actor.campusId(),actor.userId())!=1)throw missing();}
    private String imagesJson(Actor actor,List<Long> attachments){for(long a:attachments)files.owner(actor,a,"workorder",true,false);return attachments.isEmpty()?null:mapper.writeValueAsString(attachments);}
    private WorkOrderViews.WorkOrder order(long id){var rows=jdbc.query("SELECT id,category,location,description,urgency,status,version,created_at,updated_at FROM work_orders WHERE id=?",(rs,i)->new WorkOrderViews.WorkOrder(Long.toString(rs.getLong("id")),rs.getString("category"),rs.getString("location"),rs.getString("description"),rs.getString("urgency"),rs.getString("status"),serviceStage(rs.getString("status"),id),rs.getInt("version"),rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getObject("updated_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private String serviceStage(String status,long id){return switch(status){case "pending","reviewing"->"pending_review";case "accepted"->"accepted";case "processing"->"processing";case "completed"->jdbc.queryForObject("SELECT COUNT(*) FROM work_order_ratings WHERE work_order_id=?",Integer.class,id)>0?"completed":"awaiting_rating";case "rated"->"completed";case "rejected"->"rejected";default->status;};}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private BusinessException missing(){return new BusinessException(404,"NOT_FOUND","未找到请求的资源");}
    private BusinessException conflict(){return new BusinessException(409,"VERSION_CONFLICT","信息已更新，请刷新后重试");}
    private BusinessException invalid(){return new BusinessException(400,"VALIDATION_FAILED","请检查填写的信息");}
}
