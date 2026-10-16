package com.caizhitong.material.files;

import com.caizhitong.common.*;
import com.caizhitong.integration.storage.ObjectStorage;
import com.caizhitong.security.*;
import java.time.*;
import java.util.*;
import java.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AttachmentService {
    public record Attachment(AttachmentScope scope,String name,String mime,long size,String originalHash,String contentHash,
            byte[] key,String scan,String state) {}
    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final SensitiveDataCrypto crypto;
    private final Clock clock;
    private final ActorLoader actors;
    private final SnowflakeIds ids;
    private final List<LinkedAttachmentPolicy> policies;
    public AttachmentService(JdbcTemplate jdbc,ObjectStorage storage,SensitiveDataCrypto crypto,Clock clock,ActorLoader actors,SnowflakeIds ids,List<LinkedAttachmentPolicy> policies) {
        this.jdbc=jdbc;this.storage=storage;this.crypto=crypto;this.clock=clock;this.actors=actors;this.ids=ids;this.policies=List.copyOf(policies);
    }
    Attachment find(long id,boolean lock){
        var rows=jdbc.query("SELECT * FROM file_attachments WHERE id=?"+(lock?" FOR UPDATE":""),(r,i)->new Attachment(
                new AttachmentScope(r.getLong("id"),r.getLong("campus_id"),r.getLong("owner_user_id"),r.getString("access_domain"),r.getString("purpose")),
                r.getString("file_name"),r.getString("mime_type"),r.getLong("size_bytes"),r.getString("original_sha256"),r.getString("content_sha256"),r.getBytes("object_key_encrypted"),r.getString("scan_status"),r.getString("state")),id);
        if(rows.isEmpty())throw FilePurpose.missing();return rows.getFirst();
    }
    private Actor fresh(Actor actor){return actors.load(actor.userId(),actor.campusId(),actor.kind()).filter(a->"active".equals(a.status())).orElseThrow(FilePurpose::forbidden);}
    public Attachment owner(Actor actor,long id,String purpose,boolean clean,boolean lock){
        Actor current=fresh(actor);Attachment a=find(id,lock);
        if(a.scope().campusId()!=current.campusId()||a.scope().ownerUserId()!=current.userId()||!a.scope().accessDomain().equals(current.kind().name().toLowerCase(Locale.ROOT))||!a.state().equals("active")||purpose!=null&&!purpose.equals(a.scope().purpose()))throw FilePurpose.missing();
        if(clean)requireClean(a);return a;
    }
    public Attachment authorizeRead(Actor actor,long id){
        Actor current=actor==null?null:fresh(actor);Attachment a=find(id,false);
        if(!a.state().equals("active"))throw FilePurpose.missing();
        if(current!=null&&current.campusId()!=a.scope().campusId())throw FilePurpose.missing();
        if(current!=null&&current.kind()==SessionKind.ADMIN&&!current.permissions().contains("file.read"))throw FilePurpose.forbidden();
        boolean owner=current!=null&&current.userId()==a.scope().ownerUserId()&&a.scope().accessDomain().equals(current.kind().name().toLowerCase(Locale.ROOT));
        boolean linked=!Set.of("avatar","material","student_roster").contains(a.scope().purpose())&&policies.stream().anyMatch(p->p.permitsRead(current,a.scope()));
        if(!owner&&!linked)throw FilePurpose.missing();return a;
    }
    public FileViews.Metadata metadata(Actor actor,long id){Attachment a=authorizeRead(actor,id);return new FileViews.Metadata(Long.toString(id),a.name(),a.mime(),a.size(),a.scan());}
    /** Narrow DTO-only preview; neither file ownership nor generic file.read substitutes for the parent link. */
    public FileViews.Download businessPreview(Actor actor,long id,long campus,String purpose,long targetId){
        if(!Set.of("mutual_aid","issue","workorder").contains(purpose))throw FilePurpose.missing();
        Actor current=actor==null?null:fresh(actor);Attachment a=find(id,false);
        if(!"active".equals(a.state())||a.scope().campusId()!=campus||!purpose.equals(a.scope().purpose())||current!=null&&current.campusId()!=campus)throw FilePurpose.missing();
        if(policies.stream().noneMatch(p->p.permitsBusinessRead(current,a.scope(),targetId)))throw FilePurpose.missing();
        requireClean(a);
        if(!a.mime().startsWith("image/"))return null;
        return new FileViews.Download(storage.sign(crypto.decrypt("file-object-key",a.key()),Duration.ofSeconds(120)),clock.instant().plusSeconds(120));
    }
    public FileViews.Download download(Actor actor,long id){
        Attachment a=authorizeRead(actor,id);requireClean(a);
        if(a.scope().purpose().equals("student_roster")) {
            if(actor==null||!fresh(actor).permissions().contains("identity.import"))throw FilePurpose.forbidden();
            jdbc.update("INSERT INTO admin_audit_logs(id,operator_id,action,target_type,target_id,detail_json,created_at) VALUES(?,?,'file.roster.read','attachment',?,'{}',?)",ids.nextId(),actor.userId(),id,UploadService.utc(clock.instant()));
        }
        String url=storage.sign(crypto.decrypt("file-object-key",a.key()),Duration.ofSeconds(120));
        return new FileViews.Download(url,clock.instant().plusSeconds(120));
    }
    public byte[] readCleanOwner(Actor actor,long id,String purpose,int maxBytes){
        Attachment a=owner(actor,id,purpose,true,false);
        if(maxBytes<=0||a.size()>maxBytes)throw com.caizhitong.identity.IdentityInputs.invalid();
        try(InputStream input=storage.open(crypto.decrypt("file-object-key",a.key()))) {
            byte[] bytes=input.readNBytes(maxBytes+1);
            if(bytes.length>maxBytes||bytes.length!=a.size()||!FileFormat.sha256(bytes).equals(a.contentHash()))throw FilePurpose.conflict();
            return bytes;
        }catch(IOException e){throw FilePurpose.unavailable();}
    }
    static void requireClean(Attachment a){if(!a.scan().equals("clean")||a.contentHash()==null)throw new BusinessException(409,"FILE_NOT_CLEAN","文件尚未通过安全检查");}
}
