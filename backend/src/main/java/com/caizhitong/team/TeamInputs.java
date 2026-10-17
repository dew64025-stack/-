package com.caizhitong.team;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.caizhitong.identity.IdentityInputs;
import static com.caizhitong.identity.IdentityInputs.*;

public final class TeamInputs {
    private TeamInputs() {}
    public record Create(JsonNode title, JsonNode goal, JsonNode opportunityId, JsonNode openRoles) {
        public Create { text(title,256,false); if(goal!=null)text(goal,1024,true); if(opportunityId!=null&&!opportunityId.isNull())id(opportunityId); checkOpenRoles(openRoles); }
    }
    public record Update(JsonNode title, JsonNode goal, JsonNode openRoles, JsonNode version) {
        public Update { IdentityInputs.version(version); if(title==null&&goal==null&&openRoles==null)throw invalid(); if(title!=null)text(title,256,false); if(goal!=null)text(goal,1024,true); if(openRoles!=null)checkOpenRoles(openRoles); }
    }
    public record Apply(JsonNode teamId, JsonNode message, JsonNode openRoleId) {
        public Apply { id(teamId); if(message!=null)text(message,512,true); if(openRoleId!=null&&!openRoleId.isNull())id(openRoleId); }
    }
    public record Decision(JsonNode decision) {
        public Decision { String value=text(decision,16,false); if(!Set.of("approve","reject").contains(value))throw invalid(); }
    }
    public record Close(JsonNode version) { public Close { IdentityInputs.version(version); } }
    public record Empty() {}

    public static List<JsonNode> checkOpenRoles(JsonNode node) {
        if(node==null||node.isNull())return List.of();
        if(!node.isArray()||node.size()>10)throw invalid();
        List<JsonNode> roles=new ArrayList<>();
        for(JsonNode item:node){
            if(!item.isObject())throw invalid();
            if(item.has("id"))id(item.get("id"));
            text(item.get("role"),64,false);
            JsonNode tags=item.get("skillTags");if(tags!=null&&!tags.isNull()){if(!tags.isArray())throw invalid();tags.forEach(t->text(t,32,false));}
            JsonNode qty=item.get("quantity");if(qty==null||!qty.isIntegralNumber()||!qty.canConvertToInt()||qty.asInt()<1||qty.asInt()>20)throw invalid();
            roles.add(item);
        }
        return List.copyOf(roles);
    }
}
