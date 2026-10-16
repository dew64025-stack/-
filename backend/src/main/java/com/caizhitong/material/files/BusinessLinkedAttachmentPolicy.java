package com.caizhitong.material.files;

import com.caizhitong.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Only persisted links from the three supported business purposes can grant a read. */
@Component
public class BusinessLinkedAttachmentPolicy implements LinkedAttachmentPolicy {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    public BusinessLinkedAttachmentPolicy(JdbcTemplate jdbc,JsonMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public boolean permitsRead(Actor actor,AttachmentScope attachment){
        return permits(actor,attachment,null);
    }
    public boolean permitsBusinessRead(Actor actor,AttachmentScope attachment,long targetId){
        return permits(actor,attachment,targetId);
    }
    private boolean permits(Actor actor,AttachmentScope attachment,Long targetId){
        if(actor!=null&&(!"active".equals(actor.status())||actor.campusId()!=attachment.campusId()))return false;
        String sql;List<Object> args=new ArrayList<>(List.of(attachment.campusId()));
        switch(attachment.purpose()){
            case "mutual_aid"->{
                sql="SELECT images_json FROM mutual_aid_posts WHERE campus_id=? AND deleted_at IS NULL AND images_json IS NOT NULL AND (status='published'";
                if(targetId!=null&&actor!=null){sql+=" OR user_id=?";args.add(actor.userId());}
                sql+=")";
            }
            case "issue"->{
                sql="SELECT images_json FROM campus_issues WHERE campus_id=? AND deleted_at IS NULL AND images_json IS NOT NULL AND (";
                if(staff(actor,"issue.read"))sql+="1=1";
                else{
                    sql+="(visibility='public' AND status IN ('published','processing','completed'))";
                    if(actor!=null){sql+=" OR user_id=?";args.add(actor.userId());}
                }
                sql+=")";
            }
            case "workorder"->{
                if(actor==null)return false;
                sql="SELECT images_json FROM work_orders WHERE campus_id=? AND deleted_at IS NULL AND images_json IS NOT NULL";
                if(!staff(actor,"workorder.read")){sql+=" AND user_id=?";args.add(actor.userId());}
            }
            default->{return false;}
        }
        if(targetId!=null){sql+=" AND id=?";args.add(targetId);}
        return jdbc.queryForList(sql,String.class,args.toArray()).stream().anyMatch(json->contains(json,attachment.attachmentId()));
    }
    private boolean staff(Actor actor,String permission){return actor!=null&&actor.kind()==SessionKind.ADMIN&&actor.permissions().contains(permission);}
    private boolean contains(String json,long id){
        var values=mapper.readTree(json);if(!values.isArray())return false;
        for(var value:values)if((value.isString()||value.isIntegralNumber())&&Long.toString(id).equals(value.asString()))return true;
        return false;
    }
}
