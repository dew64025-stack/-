package com.caizhitong.workorder;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.caizhitong.identity.IdentityInputs;
import static com.caizhitong.identity.IdentityInputs.*;

public final class WorkOrderInputs {
    private WorkOrderInputs() {}
    private static final Set<String> CATEGORIES = Set.of("dorm","facility","other");
    private static final Set<String> URGENCIES = Set.of("normal","urgent");
    private static final Set<String> TRANSITIONS = Set.of("reviewing","accepted","processing","completed","rejected");

    public record Create(JsonNode category, JsonNode location, JsonNode description, JsonNode urgency, JsonNode attachmentIds) {
        public Create { String c=text(category,32,false); if(!CATEGORIES.contains(c))throw invalid(); if(location!=null)text(location,255,true); text(description,1024,false); String u=text(urgency,16,false); if(!URGENCIES.contains(u))throw invalid(); checkAttachmentIds(attachmentIds); }
    }
    public record Rating(JsonNode score, JsonNode comment) {
        public Rating { if(score==null||!score.isIntegralNumber()||!score.canConvertToInt()||score.asInt()<1||score.asInt()>5)throw invalid(); if(comment!=null)text(comment,512,true); }
    }
    public record Assign(JsonNode assigneeId, JsonNode version, JsonNode reason) {
        public Assign { id(assigneeId); IdentityInputs.version(version); text(reason,512,false); }
    }
    public record Transition(JsonNode toStatus, JsonNode version, JsonNode comment) {
        public Transition { String s=text(toStatus,16,false); if(!TRANSITIONS.contains(s))throw invalid(); IdentityInputs.version(version); if(comment!=null)text(comment,512,true); }
    }
    public record Empty() {}

    public static List<Long> checkAttachmentIds(JsonNode node) { if(node==null||node.isNull())return List.of(); if(!node.isArray()||node.size()>9)throw invalid(); List<Long> ids=new ArrayList<>(); node.forEach(item->ids.add(id(item))); return List.copyOf(ids); }
}
