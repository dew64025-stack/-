package com.caizhitong.community;

import tools.jackson.databind.JsonNode;
import com.caizhitong.identity.IdentityInputs;
import static com.caizhitong.identity.IdentityInputs.*;

public final class CommunityInputs {
    private CommunityInputs() {}
    public record Create(JsonNode title, JsonNode content) {
        public Create { text(title,256,false); text(content,5000,false); }
    }
    public record Update(JsonNode title, JsonNode content, JsonNode version) {
        public Update { IdentityInputs.version(version); if(title==null&&content==null)throw invalid(); if(title!=null)text(title,256,false); if(content!=null)text(content,5000,false); }
    }
    public record Comment(JsonNode content) {
        public Comment { text(content,2048,false); }
    }
    public record Empty() {}
}
