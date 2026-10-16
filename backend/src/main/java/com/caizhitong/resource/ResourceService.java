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
    private final ServiceSources sources;
    private final tools.jackson.databind.json.JsonMapper mapper;
    private static final ZoneId CAMPUS_ZONE=ZoneId.of("Asia/Shanghai");
    public ResourceService(JdbcTemplate jdbc,CurrentActor current,SnowflakeIds ids,Clock clock,CursorCodec cursors,SensitiveDataCrypto crypto,OneTimeSecrets secrets,ServiceSources sources,tools.jackson.databind.json.JsonMapper mapper){
        this.jdbc=jdbc;this.current=current;this.ids=ids;this.clock=clock;this.cursors=cursors;this.crypto=crypto;this.secrets=secrets;this.sources=sources;this.mapper=mapper;
    }

    @BusinessWrite(student=true) public ResourceViews.Reservation reserve(ResourceInputs.Reserve input){
        Actor actor=current.requireStudent();
        long slotId=IdentityInputs.id(input.slotId());int seatNo=input.seatNo().asInt();
        long resourceId=number(one("SELECT resource_id FROM resource_slots WHERE id=?",slotId),"resource_id");
        var resource=visible(resourceId,actor.campusId(),true);LocalDateTime cutoff=expire(resourceId);
        requireConfiguration(resource);
        var slot=one("SELECT * FROM resource_slots WHERE id=? FOR UPDATE",slotId);
        Integer cap=capacity(slot,resource);
        if(!slot.get("status").equals("available")||!slotEnd(slot).isAfter(clock.instant()))throw state();
        boolean exclusive="exclusive".equals(resource.get("reservation_mode"));
        if(!exclusive&&(cap==null||cap<1||!"capacity".equals(resource.get("reservation_mode"))))throw invalid();
        if(exclusive){if(seatNo!=0)throw new BusinessException(409,"SEAT_INVALID","独占资源座位号必须为0");}
        else if(seatNo<1||seatNo>cap)throw new BusinessException(409,"SEAT_INVALID","座位号超出范围");
        if("official".equals(resource.get("source_type")))throw unavailable();
        if(occupiedSeats(slot,true,cutoff).contains(seatNo))throw reservationConflict();
        long id=ids.nextId();LocalDateTime now=now();
        String source=(String)resource.get("source_type"),status="demo".equals(source)?"confirmed":"pending";
        jdbc.update("INSERT INTO reservations(id,campus_id,user_id,slot_id,seat_no,status,source_type,version,created_at,updated_at,pending_expires_at) VALUES(?,?,?,?,?,?,?,0,?,?,?)",id,actor.campusId(),actor.userId(),slotId,seatNo,status,source,now,now,"pending".equals(status)?now.plusMinutes(15):null);
        jdbc.update("INSERT INTO resource_occupancies(id,slot_id,reservation_id,seat_no,created_at,updated_at) VALUES(?,?,?,?,?,?)",ids.nextId(),slotId,id,seatNo,now,now);
        return reservation(id);
    }

    @BusinessWrite public ResourceViews.Reservation cancel(long id,ResourceInputs.Cancel input){
        Actor actor=current.require();authorizeReservation(actor,id);
        var locked=lockReservation(id,actor.campusId());
        int actual=((Number)locked.get("version")).intValue();
        if(actual!=IdentityInputs.version(input.version()))throw conflict();
        String status=jdbc.queryForObject("SELECT status FROM reservations WHERE id=?",String.class,id);
        if(!Set.of("confirmed","pending").contains(status))throw conflict();
        jdbc.update("UPDATE reservations SET status='cancelled',version=version+1,pending_expires_at=NULL,updated_at=? WHERE id=?",now(),id);
        jdbc.update("DELETE FROM resource_occupancies WHERE reservation_id=?",id);
        return reservation(id);
    }

    @BusinessWrite public ResourceViews.CheckedIn checkIn(long id,ResourceInputs.CheckIn input){
        Actor actor=current.require();authorizeReservation(actor,id);
        var locked=lockReservation(id,actor.campusId());
        int actual=((Number)locked.get("version")).intValue();
        if(actual!=IdentityInputs.version(input.version()))throw conflict();
        checkDemoWindow(locked);
        String digest=tokenDigest(id,actor.campusId(),text(input.checkInToken(),128,false));
        if(!"valid".equals(secrets.consume("reservation-checkin",digest)))throw state();
        LocalDateTime now=now();
        jdbc.update("INSERT INTO reservation_checkins(id,reservation_id,checked_in_at,created_at) VALUES(?,?,?,?)",ids.nextId(),id,now,now);
        jdbc.update("UPDATE reservations SET status='checked_in',version=version+1,updated_at=? WHERE id=?",now,id);
        return new ResourceViews.CheckedIn(reservation(id),now.toInstant(ZoneOffset.UTC));
    }

    @BusinessWrite(permission="resource.write") public ResourceViews.Resource create(ResourceInputs.Create input){
        Actor actor=current.requirePermission("resource.write");
        long id=ids.nextId();LocalDateTime now=now();
        jdbc.update("INSERT INTO resources(id,campus_id,region_id,type,name,capacity,equipment_json,reservation_mode,status,source_type,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'active','manual',0,?,?)",
                id,actor.campusId(),region(input.regionId(),actor.campusId()),text(input.type(),32,false),text(input.name(),128,false),nullableInt(input.capacity()),json(input.equipment()),text(input.reservationMode(),16,false),now,now);
        return resource(id);
    }

    @BusinessWrite(permission="resource.write") public ResourceViews.Resource update(long id,ResourceInputs.Update input){
        Actor actor=current.requirePermission("resource.write");
        var row=one("SELECT * FROM resources WHERE id=? AND campus_id=? AND deleted_at IS NULL FOR UPDATE",id,actor.campusId());
        int actual=((Number)row.get("version")).intValue();
        if(actual!=IdentityInputs.version(input.version()))throw conflict();
        if("official".equals(row.get("source_type")))throw state();expire(id);
        String mode=input.reservationMode()==null?(String)row.get("reservation_mode"):text(input.reservationMode(),16,false);
        Integer cap=input.capacity()==null?integer(row.get("capacity")):nullableInt(input.capacity());
        if("capacity".equals(mode)&&(cap==null||cap<1))throw invalid();
        if(!mode.equals(row.get("reservation_mode"))&&!jdbc.queryForList("SELECT o.id FROM resource_occupancies o JOIN resource_slots s ON s.id=o.slot_id WHERE s.resource_id=? FOR UPDATE",Long.class,id).isEmpty())throw state();
        if(input.reservationMode()!=null){var candidate=new HashMap<>(row);candidate.put("reservation_mode",mode);candidate.put("capacity",cap);candidate.put("booking_blocked",false);requireConfiguration(candidate);}
        if(input.capacity()!=null&&cap!=null&&!jdbc.queryForList("SELECT o.id FROM resource_occupancies o JOIN resource_slots s ON s.id=o.slot_id WHERE s.resource_id=? AND o.seat_no>? FOR UPDATE",Long.class,id,cap).isEmpty())throw state();
        jdbc.update("UPDATE resources SET name=?,region_id=?,capacity=?,equipment_json=?,reservation_mode=?,status=?,version=version+1,updated_at=? WHERE id=?",
                input.name()==null?row.get("name"):text(input.name(),128,false),input.regionId()==null?row.get("region_id"):region(input.regionId(),actor.campusId()),cap,input.equipment()==null?row.get("equipment_json"):json(input.equipment()),mode,input.status()==null?row.get("status"):text(input.status(),16,false),now(),id);
        if(input.reservationMode()!=null)jdbc.update("UPDATE resources SET booking_blocked=FALSE WHERE id=?",id);
        return resource(id);
    }

    @BusinessWrite(permission="resource.write") public ResourceViews.Slot createSlot(long resourceId,ResourceInputs.SlotCreate input){
        Actor actor=current.requirePermission("resource.write");
        var resource=visible(resourceId,actor.campusId(),true);
        LocalDate date=ResourceInputs.checkDate(input.slotDate());LocalTime start=ResourceInputs.checkTime(input.startTime()),end=ResourceInputs.checkTime(input.endTime());
        if(!end.isAfter(start))throw invalid();
        if(!jdbc.queryForList("SELECT id FROM resource_slots WHERE resource_id=? AND slot_date=? AND start_time<? AND end_time>? FOR UPDATE",Long.class,resourceId,date,end,start).isEmpty())throw reservationConflict();
        Integer cap=input.capacity()==null||input.capacity().isNull()?integer(resource.get("capacity")):input.capacity().asInt();
        if("capacity".equals(resource.get("reservation_mode"))&&(cap==null||cap<1))throw invalid();
        long id=ids.nextId();LocalDateTime now=now();
        jdbc.update("INSERT INTO resource_slots(id,resource_id,slot_date,start_time,end_time,capacity,status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,'available',0,?,?)",
                id,resourceId,date,start,end,cap,now,now);
        return slot(id);
    }

    @BusinessWrite(permission="resource.write") public ResourceViews.Slot updateSlot(long id,ResourceInputs.SlotUpdate input){
        Actor actor=current.requirePermission("resource.write");
        long rid=number(one("SELECT resource_id FROM resource_slots WHERE id=?",id),"resource_id");visible(rid,actor.campusId(),true);expire(rid);
        int actual=((Number)one("SELECT version FROM resource_slots WHERE id=? FOR UPDATE",id).get("version")).intValue();
        if(actual!=IdentityInputs.version(input.version()))throw conflict();
        if(input.capacity()!=null&&!jdbc.queryForList("SELECT id FROM resource_occupancies WHERE slot_id=? AND seat_no>? FOR UPDATE",Long.class,id,input.capacity().asInt()).isEmpty())throw state();
        jdbc.update("UPDATE resource_slots SET status=COALESCE(?,status),capacity=COALESCE(?,capacity),version=version+1,updated_at=? WHERE id=?",
                input.status()==null?null:text(input.status(),16,false),input.capacity()==null?null:input.capacity().asInt(),now(),id);
        return slot(id);
    }

    @BusinessWrite(permission="resource.process") public ResourceViews.Reservation decide(long id,ResourceInputs.Decision input){
        Actor actor=current.requirePermission("resource.process");
        var row=lockReservation(id,actor.campusId());int actual=((Number)row.get("version")).intValue();
        if(actual!=IdentityInputs.version(input.version()))throw conflict();
        if(!"pending".equals(row.get("status")))throw state();
        if("official".equals(row.get("source_type")))throw unavailable();
        String decision=text(input.decision(),16,false);
        String status=decision.equals("approve")?"confirmed":"rejected";
        if(status.equals("confirmed")){
            var slot=one("SELECT * FROM resource_slots WHERE id=? FOR UPDATE",number(row,"slot_id"));
            requireConfiguration(visible(number(slot,"resource_id"),actor.campusId(),true));
            if(!"available".equals(slot.get("status"))||!slotEnd(slot).isAfter(clock.instant()))throw state();
        }
        if(status.equals("confirmed")&&jdbc.queryForObject("SELECT COUNT(*) FROM resource_occupancies WHERE reservation_id=?",Integer.class,id)!=1)throw state();
        jdbc.update("UPDATE reservations SET status=?,version=version+1,pending_expires_at=NULL,updated_at=? WHERE id=?",status,now(),id);
        if(status.equals("rejected"))jdbc.update("DELETE FROM resource_occupancies WHERE reservation_id=?",id);
        return reservation(id);
    }

    // This credential route is exempt from business idempotency; enforce permission explicitly below.
    @org.springframework.transaction.annotation.Transactional public ResourceViews.CheckInToken checkInToken(long id){
        Actor actor=current.requirePermission("resource.process");
        var row=lockReservation(id,actor.campusId());checkDemoWindow(row);
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String digest=tokenDigest(id,actor.campusId(),token);
        Instant end=slotEnd(one("SELECT * FROM resource_slots WHERE id=?",number(row,"slot_id")));Instant expiry=clock.instant().plusSeconds(300);if(expiry.isAfter(end))expiry=end;
        if(!secrets.claim("reservation-checkin",digest,"valid",Duration.between(clock.instant(),expiry)))throw conflict();
        return new ResourceViews.CheckInToken(token,expiry);
    }

    public ResourceViews.Page list(long campus,String type,String regionId,String cursor,Integer limit){
        checkType(type);Long region=regionId==null?null:id(regionId);
        int size=PageLimits.resolve(limit);
        String scope="resources:"+campus+":"+type+":"+region;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM resources WHERE campus_id=? AND status='active' AND deleted_at IS NULL AND id<?";
        List<Object> args=new ArrayList<>(List.of(campus,before));
        if(type!=null){sql+=" AND type=?";args.add(type);}
        if(region!=null){sql+=" AND region_id=?";args.add(region);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::resource).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new ResourceViews.Page(page,next,more);
    }

    public ResourceViews.Resource detail(long id,long campus){visible(id,campus,false);return resource(id);}

    public List<ResourceViews.Slot> slots(long resourceId,long campus,String date){
        visible(resourceId,campus,false);
        return jdbc.queryForList("SELECT id FROM resource_slots WHERE resource_id=? AND slot_date=? ORDER BY start_time,id",Long.class,resourceId,date(date)).stream().map(this::slot).toList();
    }

    public ResourceViews.SlotAvailability slotAvailability(long slotId){
        Actor actor=current.requireStudent();
        var s=slot(slotId);
        long resourceId=Long.parseLong(s.resourceId());
        visible(resourceId,actor.campusId(),false);
        return availability(s,slotId);
    }

    public ResourceViews.Availability resourceAvailability(long resourceId,String date,String slotId){
        Actor actor=current.requireStudent();
        visible(resourceId,actor.campusId(),false);
        ResourceViews.Resource resource=resource(resourceId);
        List<ResourceViews.AvailabilityItem> items=new ArrayList<>();
        var selected=slots(resourceId,actor.campusId(),date);
        if(slotId!=null){var s=slot(id(slotId));if(!s.resourceId().equals(Long.toString(resourceId))||!s.slotDate().equals(date))throw missing();selected=List.of(s);}
        for(ResourceViews.Slot s:selected){ResourceViews.SlotAvailability a=availability(s,Long.parseLong(s.id()));items.add(new ResourceViews.AvailabilityItem(s,a.availableSeatNos(),a.canReserve(),a.unavailableReason()));}
        return new ResourceViews.Availability(resource,date,items);
    }

    public ResourceViews.ReservationPage myReservations(String status,String dateFrom,String dateTo,String cursor,Integer limit){
        Actor actor=current.require();int size=PageLimits.resolve(limit);
        checkStatus(status);LocalDate from=dateFrom==null?null:date(dateFrom),to=dateTo==null?null:date(dateTo);if(from!=null&&to!=null&&from.isAfter(to))throw invalid();
        String scope="reservations-mine:"+actor.campusId()+":"+actor.userId()+":"+status+":"+from+":"+to;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM reservations WHERE user_id=? AND campus_id=? AND deleted_at IS NULL AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.userId(),actor.campusId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        if(from!=null){sql+=" AND slot_id IN (SELECT id FROM resource_slots WHERE slot_date>=?)";args.add(from);}
        if(to!=null){sql+=" AND slot_id IN (SELECT id FROM resource_slots WHERE slot_date<=?)";args.add(to);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::reservation).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new ResourceViews.ReservationPage(page,next,more);
    }

    public ResourceViews.Reservation reservationDetail(long id){
        Actor actor=current.require();
        var rows=jdbc.queryForList("SELECT user_id,campus_id FROM reservations WHERE id=? AND deleted_at IS NULL",id);
        if(rows.isEmpty())throw missing();
        boolean owner=number(rows.getFirst(),"user_id")==actor.userId()&&number(rows.getFirst(),"campus_id")==actor.campusId();
        boolean admin=actor.kind()==SessionKind.ADMIN&&actor.permissions().contains("resource.read")&&number(rows.getFirst(),"campus_id")==actor.campusId();
        if(!owner&&!admin)throw missing();
        return reservation(id);
    }

    public ResourceViews.Page adminResources(String type,String cursor,Integer limit){
        checkType(type);
        Actor actor=current.requirePermission("resource.read");int size=PageLimits.resolve(limit);
        String scope="resources-admin:"+actor.campusId()+":"+type;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM resources WHERE campus_id=? AND deleted_at IS NULL AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.campusId(),before));
        if(type!=null){sql+=" AND type=?";args.add(type);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::resource).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new ResourceViews.Page(page,next,more);
    }

    public ResourceViews.ReservationPage adminReservations(String status,String resourceId,String date,String cursor,Integer limit){
        checkStatus(status);Long resource=resourceId==null?null:id(resourceId);LocalDate day=date==null?null:date(date);
        Actor actor=current.requirePermission("resource.read");int size=PageLimits.resolve(limit);
        String scope="reservations-admin:"+actor.campusId()+":"+status+":"+resource+":"+day;
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM reservations WHERE campus_id=? AND deleted_at IS NULL AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.campusId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        if(resource!=null){sql+=" AND slot_id IN (SELECT id FROM resource_slots WHERE resource_id=?)";args.add(resource);}
        if(day!=null){sql+=" AND slot_id IN (SELECT id FROM resource_slots WHERE slot_date=?)";args.add(day);}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;
        var page=found.stream().limit(size).map(this::reservation).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;
        return new ResourceViews.ReservationPage(page,next,more);
    }

    private void authorizeReservation(Actor actor,long id){if(jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE id=? AND campus_id=? AND user_id=?",Integer.class,id,actor.campusId(),actor.userId())!=1)throw missing();}
    private ResourceViews.Resource resource(long id){var rows=jdbc.query("SELECT * FROM resources WHERE id=?",(rs,i)->new ResourceViews.Resource(rs.getString("id"),rs.getString("name"),rs.getString("type"),rs.getString("region_id"),integer(rs.getObject("capacity")),rs.getString("equipment_json")==null?null:mapper.readTree(rs.getString("equipment_json")),rs.getString("status"),rs.getString("reservation_mode"),rs.getInt("version"),sources.read(rs.getLong("campus_id"),"resource",id,rs.getString("source_type"),rs.getObject("updated_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),new ResourceViews.BookingRules(CAMPUS_ZONE.getId(),15,true)),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private ResourceViews.Slot slot(long id){var s=one("SELECT * FROM resource_slots WHERE id=?",id);long rid=number(s,"resource_id");var r=one("SELECT * FROM resources WHERE id=?",rid);Integer cap=capacity(s,r);boolean exclusive="exclusive".equals(r.get("reservation_mode"));int total=exclusive?1:cap==null?0:cap;
        int count=(int)occupiedSeats(s,false,now()).stream().filter(seat->exclusive?seat==0:seat>=1&&seat<=total).count();
        int remaining=validConfiguration(r,false)?Math.max(0,total-count):0;
        return new ResourceViews.Slot(Long.toString(id),Long.toString(rid),day(s).toString(),time(s,"start_time").toString(),time(s,"end_time").toString(),cap,(String)s.get("status"),remaining,((Number)s.get("version")).intValue(),sources.read(number(r,"campus_id"),"resource",rid,(String)r.get("source_type"),instant(s,"updated_at")));}
    private ResourceViews.SlotAvailability availability(ResourceViews.Slot s,long slotId){
        var r=resource(Long.parseLong(s.resourceId()));Integer cap=s.capacity();boolean exclusive="exclusive".equals(r.reservationMode());
        if(!validConfiguration(one("SELECT * FROM resources WHERE id=?",Long.parseLong(s.resourceId())),false))return new ResourceViews.SlotAvailability(s,r.reservationMode(),List.of(),false,"预约配置需要管理员修复");
        var rawSlot=one("SELECT * FROM resource_slots WHERE id=?",slotId);
        Set<Integer> occ=occupiedSeats(rawSlot,false,now());
        List<Integer> available=new ArrayList<>();
        int limit=exclusive?0:cap==null?0:cap;
        if(exclusive){if(!occ.contains(0))available.add(0);}
        else for(int n=1;n<=limit;n++)if(!occ.contains(n))available.add(n);
        boolean canReserve=s.status().equals("available")&&!available.isEmpty()&&slotEnd(rawSlot).isAfter(clock.instant())&&!"official".equals(r.sourceFields().sourceType());
        String reason=canReserve?null:available.isEmpty()?"已满":"时段不可用";
        return new ResourceViews.SlotAvailability(s,exclusive?"exclusive":"capacity",available,canReserve,reason);
    }
    private ResourceViews.Reservation reservation(long id){var v=one("SELECT * FROM reservations WHERE id=?",id);var s=slot(number(v,"slot_id"));var r=resource(Long.parseLong(s.resourceId()));return new ResourceViews.Reservation(Long.toString(id),new ResourceViews.ResourceReference(r.id(),r.name(),r.type()),s,((Number)v.get("seat_no")).intValue(),(String)v.get("status"),((Number)v.get("version")).intValue(),instant(v,"created_at"),sources.read(number(v,"campus_id"),"reservation",id,(String)v.get("source_type"),instant(v,"updated_at")),instant(v,"pending_expires_at"));}
    private Map<String,Object> lockReservation(long id,long campus){var link=one("SELECT s.resource_id FROM reservations v JOIN resource_slots s ON s.id=v.slot_id WHERE v.id=? AND v.campus_id=? AND v.deleted_at IS NULL",id,campus);long rid=number(link,"resource_id");one("SELECT id FROM resources WHERE id=? FOR UPDATE",rid);expire(rid);return one("SELECT * FROM reservations WHERE id=? AND campus_id=? AND deleted_at IS NULL FOR UPDATE",id,campus);}
    private LocalDateTime expire(long resource){
        LocalDateTime cutoff=now();
        jdbc.update("DELETE FROM resource_occupancies WHERE reservation_id IN (SELECT v.id FROM reservations v JOIN resource_slots s ON s.id=v.slot_id WHERE s.resource_id=? AND v.status='pending' AND v.pending_expires_at<=?)",resource,cutoff);
        jdbc.update("UPDATE reservations SET status='cancelled',version=version+1,updated_at=?,pending_expires_at=NULL WHERE status='pending' AND pending_expires_at<=? AND slot_id IN (SELECT id FROM resource_slots WHERE resource_id=?)",cutoff,cutoff,resource);
        return cutoff;
    }
    private Set<Integer> occupiedSeats(Map<String,Object> slot,boolean lock,LocalDateTime cutoff){
        return new HashSet<>(jdbc.queryForList("SELECT o.seat_no FROM resource_occupancies o JOIN resource_slots s ON s.id=o.slot_id JOIN reservations v ON v.id=o.reservation_id WHERE s.resource_id=? AND s.slot_date=? AND s.start_time<? AND s.end_time>? AND (v.status<>'pending' OR v.pending_expires_at>?)"+(lock?" FOR UPDATE":""),Integer.class,
                number(slot,"resource_id"),slot.get("slot_date"),slot.get("end_time"),slot.get("start_time"),cutoff));
    }
    private void checkDemoWindow(Map<String,Object> row){
        if(!"confirmed".equals(row.get("status"))||!"demo".equals(row.get("source_type")))throw state();
        var s=one("SELECT * FROM resource_slots WHERE id=?",number(row,"slot_id"));
        visible(number(s,"resource_id"),number(row,"campus_id"),false);
        if(clock.instant().isBefore(slotStart(s))||!clock.instant().isBefore(slotEnd(s)))throw state();
    }
    private String tokenDigest(long id,long campus,String token){return HexFormat.of().formatHex(crypto.searchHash("reservation-checkin",campus+":"+id+":"+token));}
    private Map<String,Object> visible(long id,long campus,boolean lock){return one("SELECT * FROM resources WHERE id=? AND campus_id=? AND status='active' AND deleted_at IS NULL"+(lock?" FOR UPDATE":""),id,campus);}
    private Long region(tools.jackson.databind.JsonNode node,long campus){if(node==null||node.isNull())return null;long id=id(node);if(jdbc.queryForObject("SELECT COUNT(*) FROM campus_regions WHERE id=? AND campus_id=? AND status='active' AND deleted_at IS NULL",Integer.class,id,campus)!=1)throw missing();return id;}
    private Map<String,Object> one(String sql,Object...args){
        var rows=jdbc.query(sql,(rs,index)->{
            Map<String,Object> row=new org.springframework.util.LinkedCaseInsensitiveMap<>();var metadata=rs.getMetaData();
            for(int column=1;column<=metadata.getColumnCount();column++){
                Object value=switch(metadata.getColumnType(column)){
                    case java.sql.Types.DATE->rs.getObject(column,LocalDate.class);
                    case java.sql.Types.TIME->rs.getObject(column,LocalTime.class);
                    case java.sql.Types.TIMESTAMP->rs.getObject(column,LocalDateTime.class);
                    default->rs.getObject(column);
                };row.put(metadata.getColumnLabel(column),value);
            }return row;
        },args);if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static Integer integer(Object v){return v==null?null:((Number)v).intValue();}
    private static Integer nullableInt(tools.jackson.databind.JsonNode node){return node==null||node.isNull()?null:node.asInt();}
    private String json(tools.jackson.databind.JsonNode node){return node==null||node.isNull()?null:mapper.writeValueAsString(node);}
    private LocalDate date(String date){try{return LocalDate.parse(date);}catch(DateTimeException|NullPointerException e){throw invalid();}}
    private static LocalDate day(Map<String,Object> row){Object value=row.get("slot_date");return value instanceof java.sql.Date d?d.toLocalDate():(LocalDate)value;}
    private static LocalTime time(Map<String,Object> row,String key){Object value=row.get(key);return value instanceof java.sql.Time t?t.toLocalTime():(LocalTime)value;}
    private Instant slotStart(Map<String,Object> row){return day(row).atTime(time(row,"start_time")).atZone(CAMPUS_ZONE).toInstant();}
    private Instant slotEnd(Map<String,Object> row){return day(row).atTime(time(row,"end_time")).atZone(CAMPUS_ZONE).toInstant();}
    private static Instant instant(Map<String,Object> row,String key){Object v=row.get(key);return v==null?null:v instanceof java.sql.Timestamp t?t.toInstant():((LocalDateTime)v).toInstant(ZoneOffset.UTC);}
    private static Integer capacity(Map<String,Object> slot,Map<String,Object> resource){return integer(slot.get("capacity")==null?resource.get("capacity"):slot.get("capacity"));}
    private void requireConfiguration(Map<String,Object> resource){if(!validConfiguration(resource,true))throw new BusinessException(409,"RESERVATION_CONFIGURATION_INVALID","预约配置与既有占用不一致，请联系管理员");}
    private boolean validConfiguration(Map<String,Object> resource,boolean lock){
        Object blocked=resource.get("booking_blocked");if(Boolean.TRUE.equals(blocked)||blocked instanceof Number n&&n.intValue()!=0)return false;
        boolean exclusive="exclusive".equals(resource.get("reservation_mode"));if(!exclusive&&!"capacity".equals(resource.get("reservation_mode")))return false;
        var occupied=jdbc.queryForList("SELECT o.seat_no,s.capacity FROM resource_occupancies o JOIN resource_slots s ON s.id=o.slot_id WHERE s.resource_id=?"+(lock?" FOR UPDATE":""),number(resource,"id"));
        for(var row:occupied){int seat=((Number)row.get("seat_no")).intValue();Integer cap=capacity(row,resource);if(exclusive?seat!=0:seat<1||cap==null||cap<seat)return false;}return true;
    }
    private void checkType(String type){if(type!=null&&!Set.of("library_seat","classroom","equipment").contains(type))throw invalid();}
    private void checkStatus(String status){if(status!=null&&!Set.of("pending","confirmed","checked_in","completed","cancelled","no_show","rejected").contains(status))throw invalid();}
    private BusinessException state(){return new BusinessException(409,"STATE_CONFLICT","当前状态不允许此操作");}
    private BusinessException unavailable(){return new BusinessException(503,"SERVICE_UNAVAILABLE","官方预约服务尚不可用");}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private BusinessException missing(){return new BusinessException(404,"NOT_FOUND","未找到请求的资源");}
    private BusinessException conflict(){return new BusinessException(409,"VERSION_CONFLICT","信息已更新，请刷新后重试");}
    private BusinessException reservationConflict(){return new BusinessException(409,"RESERVATION_CONFLICT","该座位已被预约");}
    private BusinessException invalid(){return new BusinessException(400,"VALIDATION_FAILED","请检查填写的信息");}
}
