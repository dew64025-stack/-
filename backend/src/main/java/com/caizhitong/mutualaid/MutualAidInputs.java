package com.caizhitong.mutualaid;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.caizhitong.identity.IdentityInputs;
import static com.caizhitong.identity.IdentityInputs.*;

/** JSON nodes retain missing/null kinds; validation happens before any I/O. */
public final class MutualAidInputs {
    private MutualAidInputs() {}
    private static final Set<String> CATEGORIES = Set.of("lost_found","secondhand","textbook","skill","borrow");
    private static final Set<String> TRADING = Set.of("secondhand","textbook");
    private static final Set<String> POST_ACTIONS = Set.of("reserve","complete","cancel");
    private static final Set<String> BORROW_ACTIONS = Set.of("approve","cancel","confirm_handover","confirm_return");
    private static final Set<String> MATCH_ACTIONS = Set.of("verify","confirm_return","ignore");

    public record Create(JsonNode category, JsonNode title, JsonNode content, JsonNode detail, JsonNode attachmentIds, JsonNode price, JsonNode exchangeArea) {
        public Create { String cat=checkCategory(category); text(title,256,false); if(content!=null)text(content,2048,true); checkDetail(cat,detail); checkAttachmentIds(attachmentIds); String p=checkPrice(price); if(p!=null&&!TRADING.contains(cat))throw invalid(); if(exchangeArea!=null)text(exchangeArea,128,true); }
    }
    public record Update(JsonNode title, JsonNode content, JsonNode detail, JsonNode attachmentIds, JsonNode price, JsonNode exchangeArea, JsonNode version) {
        public Update { IdentityInputs.version(version); if(title==null&&content==null&&detail==null&&attachmentIds==null&&price==null&&exchangeArea==null)throw invalid(); if(title!=null)text(title,256,false); if(content!=null)text(content,2048,true); if(detail!=null)checkDetail(null,detail); checkAttachmentIds(attachmentIds); checkPrice(price); if(exchangeArea!=null)text(exchangeArea,128,true); }
    }
    public record Action(JsonNode action, JsonNode version) {
        public Action { String value=text(action,16,false); if(!POST_ACTIONS.contains(value))throw invalid(); IdentityInputs.version(version); }
    }
    public record Rating(JsonNode score, JsonNode comment) {
        public Rating { if(score==null||!score.isIntegralNumber()||!score.canConvertToInt()||score.asInt()<1||score.asInt()>5)throw invalid(); if(comment!=null)text(comment,512,true); }
    }
    public record BorrowStart(JsonNode startTime, JsonNode endTime) {
        public BorrowStart { Instant start=instant(startTime),end=instant(endTime); if(end!=null&&start!=null&&!end.isAfter(start))throw invalid(); }
    }
    public record BorrowAction(JsonNode action) {
        public BorrowAction { String value=text(action,24,false); if(!BORROW_ACTIONS.contains(value))throw invalid(); }
    }
    public record MatchAction(JsonNode action, JsonNode description) {
        public MatchAction { String value=text(action,24,false); if(!MATCH_ACTIONS.contains(value))throw invalid(); if(description!=null)text(description,512,true); }
    }
    public record Empty() {}

    public static String checkCategory(JsonNode node) { String value=text(node,32,false); if(!CATEGORIES.contains(value))throw invalid(); return value; }
    public static Instant instant(JsonNode node) { if(node==null||node.isNull())return null; try{return Instant.parse(text(node,40,false));}catch(java.time.DateTimeException e){throw invalid();} }
    public static String checkPrice(JsonNode node) { if(node==null||node.isNull())return null; String value=text(node,20,false); if(!value.matches("[0-9]{1,8}(\\.[0-9]{1,2})?"))throw invalid(); return value; }
    public static List<Long> checkAttachmentIds(JsonNode node) { if(node==null||node.isNull())return List.of(); if(!node.isArray()||node.size()>9)throw invalid(); List<Long> ids=new ArrayList<>(); node.forEach(item->ids.add(id(item))); return List.copyOf(ids); }
    public static void checkDetail(String cat,JsonNode node) {
        if(node==null||node.isNull())return;
        if(!node.isObject())throw invalid();
        if(cat==null)return;
        switch(cat){
            case "borrow"->{oneOf(node.get("kind"),Set.of("offer","request"));text(node.get("itemName"),128,false);Instant from=instant(node.get("availableFrom")),to=instant(node.get("availableTo"));if(from!=null&&to!=null&&!to.isAfter(from))throw invalid();}
            case "lost_found"->{oneOf(node.get("kind"),Set.of("lost","found"));if(instant(node.get("occurredAt"))==null)throw invalid();text(node.get("place"),256,false);if(node.has("privateProofHint"))text(node.get("privateProofHint"),512,true);}
            case "secondhand"->{oneOf(node.get("condition"),Set.of("new","like_new","good","used"));text(node.get("itemType"),128,false);}
            case "textbook"->{text(node.get("bookTitle"),256,false);oneOf(node.get("condition"),Set.of("new","like_new","good","used"));}
            case "skill"->{for(String key:List.of("offeredSkills","wantedSkills")){JsonNode values=node.get(key);if(values==null||!values.isArray()||values.size()>20)throw invalid();values.forEach(value->text(value,64,false));}}
            default->throw invalid();
        }
    }
    private static void oneOf(JsonNode value,Set<String> allowed){if(!allowed.contains(text(value,32,false)))throw invalid();}
}
