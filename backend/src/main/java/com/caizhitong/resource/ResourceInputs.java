package com.caizhitong.resource;

import tools.jackson.databind.JsonNode;
import java.time.*;
import java.util.Set;
import com.caizhitong.identity.IdentityInputs;
import static com.caizhitong.identity.IdentityInputs.*;

public final class ResourceInputs {
    private ResourceInputs() {}
    private static final Set<String> TYPES = Set.of("library_seat","classroom","equipment");
    private static final Set<String> SLOT_STATUS = Set.of("available","maintenance");
    private static final Set<String> DECISIONS = Set.of("approve","reject");

    public record Create(JsonNode type, JsonNode name, JsonNode regionId, JsonNode capacity, JsonNode equipment,JsonNode reservationMode) {
        public Create { String t=text(type,32,false); if(!TYPES.contains(t))throw invalid(); text(name,128,false); if(regionId!=null&&!regionId.isNull())id(regionId); if(capacity!=null&&!capacity.isNull())positive(capacity); mode(reservationMode); checkEquipment(equipment); if("capacity".equals(text(reservationMode,16,false))&&(capacity==null||capacity.isNull()))throw invalid(); }
    }
    public record Update(JsonNode name, JsonNode capacity, JsonNode status, JsonNode version,JsonNode regionId,JsonNode equipment,JsonNode reservationMode) {
        public Update { IdentityInputs.version(version); if(name==null&&capacity==null&&status==null&&regionId==null&&equipment==null&&reservationMode==null)throw invalid(); if(name!=null)text(name,128,false); if(capacity!=null&&!capacity.isNull())positive(capacity); if(regionId!=null&&!regionId.isNull())id(regionId); if(equipment!=null)checkEquipment(equipment); if(reservationMode!=null)mode(reservationMode); if(status!=null){String s=text(status,16,false); if(!Set.of("active","inactive").contains(s))throw invalid();} }
    }
    public record SlotCreate(JsonNode slotDate, JsonNode startTime, JsonNode endTime, JsonNode capacity) {
        public SlotCreate { checkDate(slotDate); checkTime(startTime); checkTime(endTime); if(capacity!=null&&!capacity.isNull()){if(!capacity.isIntegralNumber()||!capacity.canConvertToInt()||capacity.asInt()<1)throw invalid();} }
    }
    public record SlotUpdate(JsonNode status, JsonNode capacity, JsonNode version, JsonNode reason) {
        public SlotUpdate { IdentityInputs.version(version); if(status==null&&capacity==null)throw invalid(); if(status!=null){String s=text(status,16,false); if(!SLOT_STATUS.contains(s))throw invalid();} if(capacity!=null){if(!capacity.isIntegralNumber()||!capacity.canConvertToInt()||capacity.asInt()<1)throw invalid();} text(reason,512,false); }
    }
    public record Reserve(JsonNode slotId, JsonNode seatNo) {
        public Reserve { id(slotId); if(seatNo==null||!seatNo.isIntegralNumber()||!seatNo.canConvertToInt()||seatNo.asInt()<0)throw invalid(); }
    }
    public record Cancel(JsonNode version, JsonNode reason) {
        public Cancel { IdentityInputs.version(version); if(reason!=null)text(reason,512,true); }
    }
    public record CheckIn(JsonNode version, JsonNode checkInToken) {
        public CheckIn { IdentityInputs.version(version); text(checkInToken,128,false); }
    }
    public record Decision(JsonNode decision, JsonNode version, JsonNode reason) {
        public Decision { String d=text(decision,16,false); if(!DECISIONS.contains(d))throw invalid(); IdentityInputs.version(version); text(reason,512,false); }
    }
    public record Empty() {}

    public static LocalDate checkDate(JsonNode node){try{return LocalDate.parse(text(node,16,false));}catch(DateTimeException e){throw invalid();}}
    public static LocalTime checkTime(JsonNode node){try{return LocalTime.parse(text(node,16,false));}catch(DateTimeException e){throw invalid();}}
    private static void positive(JsonNode node){if(!node.isIntegralNumber()||!node.canConvertToInt()||node.asInt()<1)throw invalid();}
    private static void mode(JsonNode node){if(!Set.of("exclusive","capacity").contains(text(node,16,false)))throw invalid();}
    private static void checkEquipment(JsonNode node){if(node!=null&&!node.isNull()&&(!node.isObject()||node.size()>64||node.toString().length()>8192))throw invalid();}
}
