package com.caizhitong.material.files;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import static com.caizhitong.identity.IdentityInputs.*;

public final class FileInputs {
    private FileInputs() {}
    public record MaterialIntent(JsonNode fileName,JsonNode mimeType,JsonNode sizeBytes,JsonNode sha256) {
        public MaterialIntent { validate(fileName,mimeType,sizeBytes,sha256); }
    }
    public record Intent(JsonNode purpose,JsonNode fileName,JsonNode mimeType,JsonNode sizeBytes,JsonNode sha256) {
        public Intent { text(purpose,32,false);validate(fileName,mimeType,sizeBytes,sha256); }
    }
    public record Empty() {}
    public record CreateMaterial(JsonNode attachmentId,JsonNode expiresAt,JsonNode tags) {
        public CreateMaterial { id(attachmentId);expiry(expiresAt);if(tags!=null)strings(tags); }
    }
    public record UpdateMaterial(JsonNode expiresAt,JsonNode tags,JsonNode version) {
        public UpdateMaterial { FileInputs.checkVersion(version);if(expiresAt==null&&tags==null)throw invalid();expiry(expiresAt);if(tags!=null)strings(tags); }
    }
    public record Append(JsonNode attachmentId,JsonNode version) {
        public Append { id(attachmentId);FileInputs.checkVersion(version); }
    }
    private static void checkVersion(JsonNode node){version(node);}
    public static Instant expiry(JsonNode node) {
        if(node==null||node.isNull())return null;
        try{return Instant.parse(text(node,40,false));}catch(java.time.DateTimeException e){throw invalid();}
    }
    private static void validate(JsonNode name,JsonNode mime,JsonNode size,JsonNode hash) {
        String value=text(name,255,false);
        if(value.equals(".")||value.equals("..")||value.chars().anyMatch(c->Character.isISOControl(c)||c=='/'||c=='\\'||c==':'))throw invalid();
        text(mime,128,false);
        if(size==null||!size.isIntegralNumber()||!size.canConvertToLong()||size.asLong()<=0)throw invalid();
        if(hash!=null&&!hash.isNull()&&!text(hash,64,false).matches("[0-9a-f]{64}"))throw invalid();
    }
}
