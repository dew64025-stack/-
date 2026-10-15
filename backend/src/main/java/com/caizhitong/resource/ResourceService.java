package com.caizhitong.resource;

import com.caizhitong.common.*;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.pagination.*;
import com.caizhitong.identity.IdentityInputs;
import com.caizhitong.identity.OneTimeSecrets;
import com.caizhitong.security.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.caizhitong.identity.IdentityInputs.*;

@Service
public class ResourceService {
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    private final OneTimeSecrets secrets;
    public ResourceService(JdbcTemplate jdbc,CurrentActor current,SnowflakeIds ids,Clock clock,CursorCodec cursors,SensitiveDataCrypto crypto,OneTimeSecrets secrets){
        this.jdbc=jdbc;this.current=current;this.ids=ids;this.clock=clock;this.cursors=cursors;this.crypto=crypto;this.secrets=secrets;
    }

    @BusinessWrite(student=true) public ResourceViews.Reservation reserve(ResourceInputs.Reserve input){
        Actor actor=current.requireStudent();
        long slotId=IdentityInputs.id(input.slotId());int seatNo=input.seatNo().asInt();
        var rows=jdbc.queryForList("SELECT resource_id,capacity,status FROM resource_slots WHERE id=? FOR UPDATE",slotId);
        if(rows.isEmpty())throw missing();
        long resourceId=(Long)rows.getFirst().get("resource_id");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM resources WHERE id=? AND campus_id=? AND status='active' AND deleted_at IS NULL",Integer.class,resourceId,actor.campusId())!=1)throw missing();
        Integer cap=(Integer)rows.getFirst().get("capacity");
        if(!rows.getFirst().get("status").equals("available"))throw conflict();
        boolean exclusive=cap==null;
        if(exclusive){if(seatNo!=0)throw new BusinessException(409,"SEAT_INVALID","独占资源座位号必须为0");}
        else if(seatNo<1||seatNo>cap)throw new BusinessException(409,"SEAT_INVALID","座位号超出范围");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE slot_id=? AND seat_no=?",Integer.class,slotId,seatNo)>0)throw reservationConflict();
        long id=ids.nextId();LocalDateTime now=now();
        jdbc.update("INSERT INTO reservations(id,campus_id,user_id,slot_id,seat_no,status,source_type,version,created_at,updated_at) VALUES(?,?,?,?,?,'confirmed','demo',0,?,?)",id,actor.campusId(),actor.userId(),slotId,seatNo,now,now);
        jdbc.update("INSERT INTO resource_occupancies(id,slot_id,reservation_id,seat_no,created_at,updated_at) VALUES(?,?,?,?,?,?)",ids.nextId(),slotId,id,seatNo,now,now);
        return reservation(id);
    }

    @BusinessWrite public ResourceViews.Reservation cancel(long id,ResourceInputs.Cancel input){
        Actor actor=current.require();authorizeReservation(actor,id);
        int actual=jdbc.queryForObject("SELECT version FROM reservations WHERE id=? FOR UPDATE",Integer.class,id);
        if(actual!=IdentityInputs.version(input.version()))throw conflict();
        String status=jdbc.queryForObject("SELECT status FROM reservations WHERE id=?",String.class,id);
        if(!Set.of("confirmed","pending").contains(status))throw conflict();
        jdbc.update("UPDATE reservations SET status='cancelled',version=version+1,updated_at=? WHERE id=?",now(),id);
        jdbc.update("DELETE FROM resource_occupancies WHERE reservation_id=?",id);
        return reservation(id);
    }

    @BusinessWrite public ResourceViews.CheckedIn checkIn(long id,ResourceInputs.CheckIn input){
        Actor actor=current.require();authorizeReservation(actor,id);
        String digest=HexFormat.of().formatHex(crypto.searchHash("reservation-checkin",Long.toString(id)));
        String expected=secrets.consume("reservation-checkin",digest);
        if(expected==null||!expected.equals(text(input.checkInToken(),128,false)))throw conflict();
        int actual=jdbc.queryForObject("SELECT version FROM reservations WHERE id=? FOR UPDATE",Integer.class,id);
        if(actual!=IdentityInputs.version(input.version()))throw conflict();
        String status=jdbc.queryForObject("SELECT status FROM reservations WHERE id=?",String.class,id);
        if(!status.equals("confirmed"))throw conflict();
        LocalDateTime now=now();
        jdbc.update("INSERT INTO reservation_checkins(id,reservation_id,checked_in_at,created_at) VALUES(?,?,?,?)",ids.nextId(),id,now,now);
        jdbc.update("UPDATE reservations SET status='checked_in',version=version+1,updated_at=? WHERE id=?",now,id);
        return new ResourceViews.CheckedIn(Long.toString(id),"checked_in",now.toInstant(ZoneOffset.UTC));
    }

    @BusinessWrite(permission="resource.write") public ResourceViews.Resource create(ResourceInputs.Create input){
        Actor actor=current.requirePermission("resource.write");
        long id=ids.nextId();LocalDateTime now=now();
        jdbc.update("INSERT INTO resources(id,campus_id,region_id,type,name,capacity,status,source_type,version,created_at,updated_at) VALUES(?,?,?,?,?,?,'active','manual',0,?,?)",
                id,actor.campusId(),input.regionId()==null||input.regionId().isNull()?null:IdentityInputs.id(input.regionId()),text(input.type(),32,false),text(input.name(),128,false),
                input.capacity()==null||input.capacity().isNull()?null:input.capacity().asInt(),now,now);
        return resource(id);
    }

    @BusinessWrite(permission="resource.write") public ResourceViews.Resource update(long id,ResourceInputs.Update input){
        Actor actor=current.requirePermission("resource.write");
        int actual=jdbc.queryForObject("SELECT version FROM resources WHERE id=? AND campus_id=? FOR UPDATE",Integer.class,id,actor.campusId());
        if(actual!=IdentityInputs.version(input.version()))throw conflict();
        jdbc.update("UPDATE resources SET name=COALESCE(?,name),capacity=COALESCE(?,capacity),status=COALESCE(?,status),version=version+1,updated_at=? WHERE id=?",
                input.name()==null?null:text(input.name(),128,false),input.capacity()==null?null:input.capacity().asInt(),input.status()==null?null:text(input.status(),16,false),now(),id);
        return resource(id);
    }

    @BusinessWrite(permission="resource.write") public ResourceViews.Slot createSlot(long resourceId,ResourceInputs.SlotCreate input){
        Actor actor=current.requirePermission("resource.write");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM resources WHERE id=? AND campus_id=?",Integer.class,resourceId,actor.campusId())!=1)throw missing();
        LocalDate date=ResourceInputs.checkDate(input.slotDate());LocalTime start=ResourceInputs.checkTime(input.startTime()),end=ResourceInputs.checkTime(input.endTime());
        if(!end.isAfter(start))throw invalid();
        long id=ids.nextId();LocalDateTime now=now();
        jdbc.update("INSERT INTO resource_slots(id,resource_id,slot_date,start_time,end_time,capacity,status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,'available',0,?,?)",
                id,resourceId,date,start,end,input.capacity()==null||input.capacity().isNull()?null:input.capacity().asInt(),now,now);
        return slot(id);
    }

    @BusinessWrite(permission="resource.write") public ResourceViews.Slot updateSlot(long id,ResourceInputs.SlotUpdate input){
        Actor actor=current.requirePermission("resource.write");
        int actual=jdbc.queryForObject("SELECT version FROM resource_slots s JOIN resources r ON r.id=s.resource_id WHERE s.id=? AND r.campus_id=? FOR UPDATE",Integer.class,id,actor.campusId());
        if(actual!=IdentityInputs.version(input.version()))throw conflict();
        jdbc.update("UPDATE resource_slots SET status=COALESCE(?,status),capacity=COALESCE(?,capacity),version=version+1,updated_at=? WHERE id=?",
                input.status()==null?null:text(input.status(),16,false),input.capacity()==null?null:input.capacity().asInt(),now(),id);
        return slot(id);
    }

    @BusinessWrite(permission="resource.process") public ResourceViews.Reservation decide(long id,ResourceInputs.Decision input){
        Actor actor=current.requirePermission("resource.process");
        int actual=jdbc.queryForObject("SELECT version FROM reservations WHERE id=? AND campus_id=? FOR UPDATE",Integer.class,id,actor.campusId());
        if(actual!=IdentityInputs.version(input.version()))throw conflict();
        String decision=text(input.decision(),16,false);
        String status=decision.equals("approve")?"confirmed":"rejected";
        jdbc.update("UPDATE reservations SET status=?,version=version+1,updated_at=? WHERE id=?",status,now(),id);
        if(status.equals("rejected"))jdbc.update("DELETE FROM resource_occupancies WHERE reservation_id=?",id);
        return reservation(id);
    }

    @BusinessWrite(permission="resource.process") public ResourceViews.CheckInToken checkInToken(long id){
        Actor actor=current.requirePermission("resource.process");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE id=? AND campus_id=?",Integer.class,id,actor.campusId())!=1)throw missing();
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String digest=HexFormat.of().formatHex(crypto.searchHash("reservation-checkin",Long.toString(id)));
        if(!secrets.claim("reservation-checkin",digest,token,Duration.ofMinutes(5)))throw conflict();
        return new ResourceViews.CheckInToken(token,clock.instant().plusSeconds(300));
    }

    public ResourceViews.Page list(long campus,String type,String cursor,Integer limit){
        int size=PageLimits.resolve(limit);
        String scope="resources:"+campus+":"+type;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM resources WHERE campus_id=? AND status='active' AND deleted_at IS NULL AND id<?";
        List<Object> args=new ArrayList<>(List.of(campus,before));
        if(type!=null){sql+=" AND type=?";args.add(type);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::resource).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new ResourceViews.Page(page,next,more);
    }

    public ResourceViews.Resource detail(long id,long campus){var r=resource(id);if(jdbc.queryForObject("SELECT campus_id FROM resources WHERE id=?",Long.class,id)!=campus)throw missing();return r;}

    public List<ResourceViews.Slot> slots(long resourceId,long campus,String date){
        if(jdbc.queryForObject("SELECT campus_id FROM resources WHERE id=?",Long.class,resourceId)!=campus)throw missing();
        return jdbc.query("SELECT id,resource_id,slot_date,start_time,end_time,capacity,status,version FROM resource_slots WHERE resource_id=? AND slot_date=? ORDER BY start_time",
                (rs,i)->new ResourceViews.Slot(Long.toString(rs.getLong("id")),Long.toString(rs.getLong("resource_id")),rs.getDate("slot_date").toLocalDate().toString(),rs.getTime("start_time").toLocalTime().toString(),rs.getTime("end_time").toLocalTime().toString(),(Integer)rs.getObject("capacity"),rs.getString("status"),rs.getInt("version")),resourceId,LocalDate.parse(date));
    }

    public ResourceViews.SlotAvailability slotAvailability(long slotId){
        Actor actor=current.requireStudent();
        var s=slot(slotId);
        long resourceId=Long.parseLong(s.resourceId());
        if(jdbc.queryForObject("SELECT campus_id FROM resources WHERE id=?",Long.class,resourceId)!=actor.campusId())throw missing();
        return availability(s,slotId);
    }

    public ResourceViews.Availability resourceAvailability(long resourceId,String date){
        Actor actor=current.requireStudent();
        if(jdbc.queryForObject("SELECT campus_id FROM resources WHERE id=?",Long.class,resourceId)!=actor.campusId())throw missing();
        ResourceViews.Resource resource=resource(resourceId);
        List<ResourceViews.AvailabilityItem> items=new ArrayList<>();
        for(ResourceViews.Slot s:slots(resourceId,actor.campusId(),date)){ResourceViews.SlotAvailability a=availability(s,Long.parseLong(s.id()));items.add(new ResourceViews.AvailabilityItem(s,a.availableSeatNos(),a.canReserve(),a.unavailableReason()));}
        return new ResourceViews.Availability(resource,date,items);
    }

    public ResourceViews.ReservationPage myReservations(String status,String dateFrom,String dateTo,String cursor,Integer limit){
        Actor actor=current.require();int size=PageLimits.resolve(limit);
        String scope="reservations-mine:"+actor.userId()+":"+status+":"+dateFrom+":"+dateTo;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM reservations WHERE user_id=? AND campus_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.userId(),actor.campusId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::reservation).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new ResourceViews.ReservationPage(page,next,more);
    }

    public ResourceViews.Reservation reservationDetail(long id){
        Actor actor=current.require();
        var rows=jdbc.queryForList("SELECT user_id,campus_id FROM reservations WHERE id=?",id);
        if(rows.isEmpty())throw missing();
        boolean owner=(Long)rows.getFirst().get("user_id")==actor.userId()&&(Long)rows.getFirst().get("campus_id")==actor.campusId();
        boolean admin=actor.kind()==SessionKind.ADMIN&&actor.permissions().contains("resource.read")&&(Long)rows.getFirst().get("campus_id")==actor.campusId();
        if(!owner&&!admin)throw missing();
        return reservation(id);
    }

    public ResourceViews.Page adminResources(String type,String cursor,Integer limit){
        Actor actor=current.requirePermission("resource.read");int size=PageLimits.resolve(limit);
        String scope="resources-admin:"+actor.campusId()+":"+type;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM resources WHERE campus_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.campusId(),before));
        if(type!=null){sql+=" AND type=?";args.add(type);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::resource).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new ResourceViews.Page(page,next,more);
    }

    public ResourceViews.ReservationPage adminReservations(String status,String resourceId,String cursor,Integer limit){
        Actor actor=current.requirePermission("resource.read");int size=PageLimits.resolve(limit);
        String scope="reservations-admin:"+actor.campusId()+":"+status+":"+resourceId;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM reservations WHERE campus_id=? AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.campusId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::reservation).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new ResourceViews.ReservationPage(page,next,more);
    }

    private void authorizeReservation(Actor actor,long id){if(jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE id=? AND campus_id=? AND user_id=?",Integer.class,id,actor.campusId(),actor.userId())!=1)throw missing();}
    private ResourceViews.Resource resource(long id){var rows=jdbc.query("SELECT id,name,type,capacity,status,version FROM resources WHERE id=?",(rs,i)->new ResourceViews.Resource(Long.toString(rs.getLong("id")),rs.getString("name"),rs.getString("type"),(Integer)rs.getObject("capacity"),rs.getString("status"),rs.getObject("capacity")==null?"exclusive":"capacity",rs.getInt("version")),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private ResourceViews.Slot slot(long id){var rows=jdbc.query("SELECT id,resource_id,slot_date,start_time,end_time,capacity,status,version FROM resource_slots WHERE id=?",(rs,i)->new ResourceViews.Slot(Long.toString(rs.getLong("id")),Long.toString(rs.getLong("resource_id")),rs.getDate("slot_date").toLocalDate().toString(),rs.getTime("start_time").toLocalTime().toString(),rs.getTime("end_time").toLocalTime().toString(),(Integer)rs.getObject("capacity"),rs.getString("status"),rs.getInt("version")),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private ResourceViews.SlotAvailability availability(ResourceViews.Slot s,long slotId){
        Integer cap=s.capacity();boolean exclusive=cap==null;
        List<Integer> occupied=jdbc.queryForList("SELECT seat_no FROM resource_occupancies WHERE slot_id=?",Integer.class,slotId);
        Set<Integer> occ=new HashSet<>(occupied);
        List<Integer> available=new ArrayList<>();
        int limit=exclusive?0:cap;
        if(exclusive){if(!occ.contains(0))available.add(0);}
        else for(int n=1;n<=limit;n++)if(!occ.contains(n))available.add(n);
        boolean canReserve=s.status().equals("available")&&!available.isEmpty();
        String reason=!s.status().equals("available")?"时段不可用":(available.isEmpty()?"已满":null);
        return new ResourceViews.SlotAvailability(s,exclusive?"exclusive":"capacity",available,canReserve,reason);
    }
    private ResourceViews.Reservation reservation(long id){var rows=jdbc.query("SELECT id,slot_id,seat_no,status,version,created_at,updated_at FROM reservations WHERE id=?",(rs,i)->new ResourceViews.Reservation(Long.toString(rs.getLong("id")),Long.toString(rs.getLong("slot_id")),rs.getInt("seat_no"),rs.getString("status"),rs.getInt("version"),rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getObject("updated_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private BusinessException missing(){return new BusinessException(404,"NOT_FOUND","未找到请求的资源");}
    private BusinessException conflict(){return new BusinessException(409,"VERSION_CONFLICT","信息已更新，请刷新后重试");}
    private BusinessException reservationConflict(){return new BusinessException(409,"RESERVATION_CONFLICT","该座位已被预约");}
    private BusinessException invalid(){return new BusinessException(400,"VALIDATION_FAILED","请检查填写的信息");}
}
