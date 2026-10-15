package com.caizhitong.resource;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Metadata uses existing business source_type and external references. */
@Component
public class ServiceSources {
    public record Source(String sourceType,String sourceName,Instant updatedAt,boolean isStale,String officialReference,String externalId) {}
    public record Attachment(String id,String thumbnailUrl,Instant urlExpiresAt) {}
    private final JdbcTemplate jdbc;
    public ServiceSources(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Source read(long campus,String type,long id,String source,Instant updatedAt){
        var refs=jdbc.query("SELECT e.external_id,s.status FROM external_references e JOIN external_systems s ON s.id=e.external_system_id WHERE s.campus_id=? AND e.local_type=? AND e.local_id=? ORDER BY e.id LIMIT 1",(r,i)->new String[]{r.getString(1),r.getString(2)},campus,type,id);
        String name="demo".equals(source)?"校园演示提供器":"manual".equals(source)?"平台处理":null;
        return new Source(source,name,updatedAt,"official".equals(source)&&(refs.isEmpty()||!"active".equals(refs.getFirst()[1])),null,refs.isEmpty()?null:refs.getFirst()[0]);
    }
    public static List<Attachment> attachments(tools.jackson.databind.JsonNode data){
        if(data==null||!data.isArray())return List.of();
        java.util.LinkedHashSet<String> ids=new java.util.LinkedHashSet<>();
        for(var item:data){
            if(!item.isString()&&!item.isIntegralNumber())continue;
            try{ids.add(Long.toString(com.caizhitong.identity.IdentityInputs.id(item.asString())));}
            catch(com.caizhitong.common.BusinessException ignored){/* Legacy object keys are not attachment metadata. */}
        }
        return ids.stream().map(id->new Attachment(id,null,null)).toList();
    }
}
