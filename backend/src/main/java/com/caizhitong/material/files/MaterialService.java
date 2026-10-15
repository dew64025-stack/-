package com.caizhitong.material.files;

import com.caizhitong.common.*;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.pagination.*;
import com.caizhitong.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.*;

@Service
public class MaterialService {
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final AttachmentService files;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    private final UploadService uploads;
    private final List<MaterialRetentionPolicy> retention;
    public MaterialService(JdbcTemplate jdbc,CurrentActor current,AttachmentService files,SnowflakeIds ids,Clock clock,JsonMapper mapper,CursorCodec cursors,SensitiveDataCrypto crypto,UploadService uploads,List<MaterialRetentionPolicy> retention){
        this.jdbc=jdbc;this.current=current;this.files=files;this.ids=ids;this.clock=clock;this.mapper=mapper;this.cursors=cursors;this.crypto=crypto;this.uploads=uploads;this.retention=List.copyOf(retention);
    }
    public void authorize(long id,boolean includeDeleted){
        Actor actor=current.require();
        if(actor.kind()!=SessionKind.STUDENT)throw FilePurpose.forbidden();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM material_files WHERE id=? AND campus_id=? AND user_id=?"+(includeDeleted?"":" AND status='active'"),Integer.class,id,actor.campusId(),actor.userId())!=1)throw FilePurpose.missing();
    }
    private void lock(long id,int version){authorize(id,false);int actual=jdbc.queryForObject("SELECT version FROM material_files WHERE id=? FOR UPDATE",Integer.class,id);if(actual!=version)throw new BusinessException(409,"VERSION_CONFLICT","信息已更新，请刷新后重试");}
    @BusinessWrite public FileViews.Material create(FileInputs.CreateMaterial input){
        Actor actor=current.require();var a=files.owner(actor,id(input.attachmentId()),"material",true,true);
        long material=ids.nextId();LocalDateTime now=now();Instant expiry=FileInputs.expiry(input.expiresAt());
        jdbc.update("INSERT INTO material_files(id,user_id,campus_id,object_key_encrypted,current_attachment_id,file_hash,size_bytes,mime_type,file_name,expires_at,tags_json,encrypted,scan_status,status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,1,'clean','active',0,?,?)",material,actor.userId(),actor.campusId(),a.key(),a.scope().attachmentId(),a.contentHash(),a.size(),a.mime(),a.name(),expiry==null?null:UploadService.utc(expiry),mapper.writeValueAsString(input.tags()==null?List.of():strings(input.tags())),now,now);
        addVersion(material,a,1);return material(material);
    }
    @BusinessWrite public FileViews.Material update(long id,FileInputs.UpdateMaterial input){
        lock(id,version(input.version()));
        var old=material(id);Instant expiry=input.expiresAt()==null?old.expiresAt():FileInputs.expiry(input.expiresAt());
        jdbc.update("UPDATE material_files SET expires_at=?,tags_json=?,version=version+1,updated_at=? WHERE id=?",expiry==null?null:UploadService.utc(expiry),mapper.writeValueAsString(input.tags()==null?old.tags():strings(input.tags())),now(),id);return material(id);
    }
    @BusinessWrite public FileViews.Material append(long id,FileInputs.Append input){
        lock(id,version(input.version()));var a=files.owner(current.require(),id(input.attachmentId()),"material",true,true);
        int next=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM material_versions WHERE file_id=?",Integer.class,id);
        addVersion(id,a,next);
        jdbc.update("UPDATE material_files SET current_attachment_id=?,object_key_encrypted=?,file_hash=?,size_bytes=?,mime_type=?,file_name=?,scan_status='clean',version=version+1,updated_at=? WHERE id=?",a.scope().attachmentId(),a.key(),a.contentHash(),a.size(),a.mime(),a.name(),now(),id);return material(id);
    }
    private void addVersion(long id,AttachmentService.Attachment a,int number){jdbc.update("INSERT INTO material_versions(id,file_id,version_no,object_key_encrypted,attachment_id,file_hash,created_at) VALUES(?,?,?,?,?,?,?)",ids.nextId(),id,number,a.key(),a.scope().attachmentId(),a.contentHash(),now());}
    @BusinessWrite public FileViews.Deleted delete(long id,int version){
        lock(id,version);
        boolean retained=jdbc.queryForObject("SELECT COUNT(*) FROM material_checklist_items i JOIN material_versions v ON v.id=i.material_version_id WHERE v.file_id=?",Integer.class,id)>0
                ||jdbc.queryForObject("SELECT COUNT(*) FROM material_usage_records WHERE file_id=?",Integer.class,id)>0
                ||jdbc.queryForObject("SELECT COUNT(*) FROM growth_records WHERE proof_file_id=?",Integer.class,id)>0
                ||retention.stream().anyMatch(p->p.mustRetain(id));
        if(retained)throw new BusinessException(409,"MATERIAL_RETAINED","材料仍被清单或历史记录引用");
        var attachments=jdbc.queryForList("SELECT DISTINCT attachment_id FROM material_versions WHERE file_id=? AND attachment_id IS NOT NULL ORDER BY attachment_id",Long.class,id);
        for(long attachment:attachments){
            var a=files.owner(current.require(),attachment,"material",false,true);
            // All reference changes take this attachment lock. A locking read must also bypass any older RR snapshot.
            if(!jdbc.queryForList("SELECT v.id FROM material_versions v JOIN material_files m ON m.id=v.file_id WHERE v.attachment_id=? AND m.status='active' AND m.id<>? LIMIT 1 FOR UPDATE",Long.class,attachment,id).isEmpty())continue;
            jdbc.update("UPDATE file_attachments SET state='deleted',updated_at=? WHERE id=?",now(),attachment);
            uploads.cleanup(a.key(),null,attachment,now());
        }
        jdbc.update("UPDATE material_files SET status='deleted',deleted_at=?,updated_at=?,version=version+1 WHERE id=?",now(),now(),id);
        return new FileViews.Deleted(true,"pending");
    }
    private FileViews.Material material(long id){
        var rows=jdbc.query("SELECT * FROM material_files WHERE id=?",(r,i)->new FileViews.Material(r.getString("id"),r.getString("file_name"),r.getString("mime_type"),r.getLong("size_bytes"),r.getString("scan_status"),r.getString("status"),r.getObject("expires_at",LocalDateTime.class)==null?null:r.getObject("expires_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),tags(r.getString("tags_json")),null,r.getInt("version")),id);
        if(rows.isEmpty())throw FilePurpose.missing();var m=rows.getFirst();
        var versions=versions(id);if(versions.isEmpty())throw FilePurpose.conflict();
        return new FileViews.Material(m.id(),m.fileName(),m.mimeType(),m.sizeBytes(),m.scanStatus(),m.status(),m.expiresAt(),m.tags(),versions.getFirst(),m.version());
    }
    private List<String> tags(String json){if(json==null)return List.of();var node=mapper.readTree(json);if(node.isString())node=mapper.readTree(node.asString());return strings(node);}
    private List<FileViews.Version> versions(long id){return jdbc.query("SELECT id,version_no FROM material_versions WHERE file_id=? ORDER BY version_no DESC",(r,i)->new FileViews.Version(r.getString(1),r.getInt(2)),id);}
    public FileViews.MaterialDetail detail(long id){authorize(id,false);var m=material(id);return new FileViews.MaterialDetail(m.id(),m.fileName(),m.mimeType(),m.sizeBytes(),m.scanStatus(),m.status(),m.expiresAt(),m.tags(),m.currentVersion(),m.version(),versions(id));}
    public FileViews.Page list(String q,String tag,String cursor,Integer limit){
        Actor actor=current.require();int size=PageLimits.resolve(limit);q=q==null?"":q.trim();tag=tag==null?"":tag.trim();if(q.length()>255||tag.length()>32)throw invalid();
        String scope="materials:"+actor.campusId()+":"+actor.userId()+":"+HexFormat.of().formatHex(crypto.searchHash("material-list",mapper.writeValueAsString(List.of(q,tag))));
        long before=Long.MAX_VALUE;
        if(cursor!=null){var keys=cursors.decode(cursor,scope).sortKeys();if(!keys.keySet().equals(Set.of("id")))throw invalid();before=id(keys.get("id"));}
        String sql="SELECT id FROM material_files WHERE campus_id=? AND user_id=? AND status='active' AND id<?";
        List<Object> args=new ArrayList<>(List.of(actor.campusId(),actor.userId(),before));
        if(!q.isEmpty()){sql+=" AND file_name LIKE ? ESCAPE '!'";args.add("%"+escape(q)+"%");}
        if(!tag.isEmpty()){sql+=" AND tags_json LIKE ? ESCAPE '!'";args.add("%"+escape(mapper.writeValueAsString(tag))+"%");}
        sql+=" ORDER BY id DESC LIMIT ?";args.add(size+1);
        var found=jdbc.queryForList(sql,Long.class,args.toArray());boolean more=found.size()>size;var page=found.stream().limit(size).map(this::material).toList();
        String next=more?cursors.encode(new CursorPayload(scope,Map.of("id",page.getLast().id()))):null;return new FileViews.Page(page,next,more);
    }
    private String escape(String text){return text.replace("!","!!").replace("%","!%").replace("_","!_");}
    private LocalDateTime now(){return UploadService.utc(clock.instant());}
}
