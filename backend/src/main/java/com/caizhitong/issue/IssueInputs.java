package com.caizhitong.issue;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.caizhitong.identity.IdentityInputs;
import static com.caizhitong.identity.IdentityInputs.*;

public final class IssueInputs {
    private IssueInputs() {}
    private static final Set<String> CATEGORIES = Set.of("repair","canteen","express","other");
    private static final Set<String> DECISIONS = Set.of("publish","private","reject");
    private static final Set<String> TRANSITIONS = Set.of("processing","completed");
    private static final Set<String> CROWD_TYPES = Set.of("canteen","express");
    private static final Set<String> CROWD_LEVELS = Set.of("low","medium","high");

    public record Create(JsonNode title, JsonNode category, JsonNode description, JsonNode location, JsonNode attachmentIds) {
        public Create { text(title,256,false); String c=text(category,32,false); if(!CATEGORIES.contains(c))throw invalid(); if(description!=null)text(description,1024,true); checkLocation(location); checkAttachmentIds(attachmentIds); }
    }
    public record Vote(JsonNode value) {
        public Vote { if(value==null||!value.isIntegralNumber()||!value.canConvertToInt()||!Set.of(1,-1).contains(value.asInt()))throw invalid(); }
    }
    public record Evidence(JsonNode content, JsonNode attachmentIds) {
        public Evidence { if((content==null||content.isNull())&&(attachmentIds==null||attachmentIds.isNull()))throw invalid(); if(content!=null&&!content.isNull())text(content,512,false); if(attachmentIds!=null&&!attachmentIds.isNull())checkAttachmentIds(attachmentIds); }
    }
    public record Decision(JsonNode decision, JsonNode version, JsonNode reason) {
        public Decision { String d=text(decision,16,false); if(!DECISIONS.contains(d))throw invalid(); IdentityInputs.version(version); text(reason,512,false); }
    }
    public record Transition(JsonNode toStatus, JsonNode version, JsonNode comment) {
        public Transition { String s=text(toStatus,16,false); if(!TRANSITIONS.contains(s))throw invalid(); IdentityInputs.version(version); if(comment!=null)text(comment,512,true); }
    }
    public record Crowd(JsonNode locationType, JsonNode locationName, JsonNode crowdLevel) {
        public Crowd { String t=text(locationType,32,false); if(!CROWD_TYPES.contains(t))throw invalid(); text(locationName,128,false); String l=text(crowdLevel,16,false); if(!CROWD_LEVELS.contains(l))throw invalid(); }
    }
    public record Empty() {}

    public static List<Long> checkAttachmentIds(JsonNode node) { if(node==null||node.isNull())return List.of(); if(!node.isArray()||node.size()>9)throw invalid(); List<Long> ids=new ArrayList<>(); node.forEach(item->ids.add(id(item))); return List.copyOf(ids); }
    public static void checkLocation(JsonNode node) { if(node==null||node.isNull())return; if(!node.isObject())throw invalid(); if(node.hasNonNull("name"))text(node.get("name"),128,true); JsonNode lat=node.get("latitude"),lng=node.get("longitude"); if(lat==null||!lat.isNumber()||lng==null||!lng.isNumber())throw invalid(); double la=lat.asDouble(),ln=lng.asDouble(); if(la<-90||la>90||ln<-180||ln>180)throw invalid(); }
}
