package com.caizhitong.identity;

import com.caizhitong.common.BusinessException;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** JSON nodes retain missing/null and scalar kinds; validation happens before any I/O. */
public final class IdentityInputs {
    private IdentityInputs() {}
    public record Login(JsonNode code,JsonNode campusCode) { public Login { text(code,256,false);text(campusCode,64,false); } }
    public record Refresh(JsonNode refreshToken) { public Refresh { text(refreshToken,128,false); } }
    public record Logout(JsonNode refreshToken) { public Logout { if(refreshToken!=null)text(refreshToken,128,false); } }
    public record Phone(JsonNode phoneCode) { public Phone { text(phoneCode,256,false); } }
    public record Update(JsonNode nickname,JsonNode avatarAttachmentId,JsonNode version) {
        public Update { IdentityInputs.version(version);if(nickname==null&&avatarAttachmentId==null)throw invalid();if(nickname!=null)text(nickname,64,false);if(avatarAttachmentId!=null&&!avatarAttachmentId.isNull())id(avatarAttachmentId); }
    }
    public record ProfileUpdate(JsonNode college,JsonNode grade,JsonNode skills,JsonNode interests,JsonNode version) {
        public ProfileUpdate { IdentityInputs.version(version);if(college==null&&grade==null&&skills==null&&interests==null)throw invalid();if(college!=null)text(college,128,true);if(grade!=null)text(grade,32,true);if(skills!=null)strings(skills);if(interests!=null)strings(interests); }
    }
    public record Student(JsonNode studentNumber) { public Student { text(studentNumber,32,false); } }
    public record Decision(JsonNode decision,JsonNode reason,JsonNode version) {
        public Decision { String value=text(decision,16,false);if(!Set.of("approve","reject").contains(value))throw invalid();text(reason,value.equals("reject")?255:512,false);IdentityInputs.version(version); }
    }
    public record Import(JsonNode attachmentId) { public Import { id(attachmentId); } }
    public record Password(JsonNode username,JsonNode password) { public Password { text(username,64,false);if(text(password,72,false).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw invalid(); } }
    public record Mfa(JsonNode challengeId,JsonNode code) { public Mfa { text(challengeId,128,false);totp(code); } }
    public record Reauth(JsonNode mfaCode) { public Reauth { totp(mfaCode); } }
    public record Role(JsonNode roleCode,JsonNode campusId,JsonNode reauthToken,JsonNode reason) { public Role {text(roleCode,64,false);id(campusId);text(reauthToken,128,false);text(reason,512,false);} }
    public record Revoke(JsonNode reason) { public Revoke { text(reason,512,false); } }
    public record Empty() {}
    public static String text(JsonNode node,int max,boolean nullable) {
        if(node!=null&&node.isNull()&&nullable)return null;
        if(node==null||!node.isString()||node.asString().isBlank()||node.asString().length()>max)throw invalid();return node.asString();
    }
    public static long id(JsonNode node) { return id(text(node,20,false)); }
    public static long id(String value) {if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw invalid();try{return Long.parseLong(value);}catch(NumberFormatException e){throw invalid();} }
    public static int version(JsonNode node) {if(node==null||!node.isIntegralNumber()||!node.canConvertToInt()||node.asInt()<0)throw invalid();return node.asInt();}
    public static List<String> strings(JsonNode node) {if(node==null||!node.isArray()||node.size()>32)throw invalid();Set<String> result=new LinkedHashSet<>();node.forEach(item->result.add(text(item,64,false)));return List.copyOf(result);}
    public static String totp(JsonNode node) {String code=text(node,6,false);if(!code.matches("[0-9]{6}"))throw invalid();return code;}
    public static BusinessException invalid(){return new BusinessException(400,"VALIDATION_FAILED","请检查填写的信息");}
}
