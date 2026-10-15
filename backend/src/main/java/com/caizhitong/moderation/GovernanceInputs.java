package com.caizhitong.moderation;

import tools.jackson.databind.JsonNode;
import com.caizhitong.identity.IdentityInputs;
import static com.caizhitong.identity.IdentityInputs.*;
import java.util.Set;

public final class GovernanceInputs {
    private GovernanceInputs() {}
    private static final Set<String> REPORT_TYPES = Set.of("community_post","community_comment","mutual_aid_post","message");
    private static final Set<String> BLOCK_TYPES = Set.of("user","community_post","mutual_aid_post");
    private static final Set<String> PENALTY_TYPES = Set.of("remind","rate_limit","take_down","mute","ban");

    public record Report(JsonNode targetType, JsonNode targetId, JsonNode reason) {
        public Report { String t=text(targetType,32,false); if(!REPORT_TYPES.contains(t))throw invalid(); id(targetId); text(reason,512,false); }
    }
    public record Appeal(JsonNode penaltyId, JsonNode reason) {
        public Appeal { id(penaltyId); text(reason,512,false); }
    }
    public record Empty() {}
    public record Penalty(JsonNode userId, JsonNode type, JsonNode reason, JsonNode expiresAt, JsonNode relatedReportId) {
        public Penalty { id(userId); String t=text(type,32,false); if(!PENALTY_TYPES.contains(t))throw invalid(); text(reason,512,false); if(expiresAt!=null&&!expiresAt.isNull())text(expiresAt,40,false); if(relatedReportId!=null&&!relatedReportId.isNull())id(relatedReportId); }
    }
    public record Lift(JsonNode reason) { public Lift { text(reason,512,false); } }
    public record Decision(JsonNode decision, JsonNode reason) {
        public Decision { String d=text(decision,16,false); if(!Set.of("approve","reject").contains(d))throw invalid(); text(reason,512,false); }
    }
    public record ReportDecision(JsonNode decision, JsonNode reason, JsonNode action) {
        public ReportDecision { String d=text(decision,16,false); if(!Set.of("resolve","dismiss").contains(d))throw invalid(); text(reason,512,false); if(action!=null)text(action,32,true); }
    }
    public record ModerationDecision(JsonNode decision, JsonNode reason) {
        public ModerationDecision { String d=text(decision,16,false); if(!Set.of("approve","reject","restrict").contains(d))throw invalid(); text(reason,512,false); }
    }
    public record Investigation(JsonNode targetType, JsonNode targetId, JsonNode reportId, JsonNode reason, JsonNode reauthToken) {
        public Investigation { text(targetType,32,false); id(targetId); if(reportId!=null&&!reportId.isNull())id(reportId); text(reason,512,false); text(reauthToken,128,false); }
    }
    public static String blockType(JsonNode node){String t=text(node,32,false);if(!BLOCK_TYPES.contains(t))throw invalid();return t;}
}
