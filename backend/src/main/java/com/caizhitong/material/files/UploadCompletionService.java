package com.caizhitong.material.files;

import com.caizhitong.common.*;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class UploadCompletionService {
    private record Upload(long attachmentId,String state,LocalDateTime expiresAt,String domain,String purpose,
            String name,String mime,long size,String originalHash,byte[] key) {}
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    public UploadCompletionService(JdbcTemplate jdbc,CurrentActor current,SnowflakeIds ids,Clock clock){this.jdbc=jdbc;this.current=current;this.ids=ids;this.clock=clock;}
    public void authorize(Actor actor,long id){
        var rows=jdbc.queryForList("SELECT purpose,mime_type FROM file_uploads WHERE id=? AND campus_id=? AND owner_user_id=? AND access_domain=?",id,actor.campusId(),actor.userId(),actor.kind().name().toLowerCase(Locale.ROOT));
        if(rows.isEmpty())throw FilePurpose.missing();
        FilePurpose.authorize(actor,(String)rows.getFirst().get("purpose"),(String)rows.getFirst().get("mime_type"));
    }
    @BusinessWrite public FileViews.Completed student(long id){return complete(current.require(),id);}
    @BusinessWrite(permission="file.upload") public FileViews.Completed admin(long id){return complete(current.requirePermission("file.upload"),id);}
    private FileViews.Completed complete(Actor actor,long id){
        authorize(actor,id);
        var row=jdbc.queryForObject("SELECT * FROM file_uploads WHERE id=? FOR UPDATE",(r,i)->new Upload(
                r.getLong("attachment_id"),r.getString("state"),r.getObject("expires_at",LocalDateTime.class),
                r.getString("access_domain"),r.getString("purpose"),r.getString("file_name"),r.getString("mime_type"),
                r.getLong("size_bytes"),r.getString("original_sha256"),r.getBytes("object_key_encrypted")),id);
        if(row.attachmentId()!=0){long a=row.attachmentId();long j=jdbc.queryForObject("SELECT id FROM async_jobs WHERE job_type='file_scan' AND file_attachment_id=?",Long.class,a);return new FileViews.Completed(Long.toString(a),"pending",Long.toString(j));}
        if(!row.state().equals("uploaded")||!row.expiresAt().isAfter(UploadService.utc(clock.instant())))throw FilePurpose.conflict();
        long attachment=ids.nextId(),job=ids.nextId();LocalDateTime now=UploadService.utc(clock.instant());
        jdbc.update("INSERT INTO file_attachments(id,upload_id,campus_id,owner_user_id,access_domain,purpose,file_name,mime_type,size_bytes,original_sha256,object_key_encrypted,scan_status,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'pending','active',?,?)",attachment,id,actor.campusId(),actor.userId(),row.domain(),row.purpose(),row.name(),row.mime(),row.size(),row.originalHash(),row.key(),now,now);
        jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,campus_id,owner_user_id,access_domain,file_attachment_id,retry_count,max_retries,created_at,updated_at) VALUES(?,'file_scan','{}','pending',?,?,?,?,0,3,?,?)",job,actor.campusId(),actor.userId(),row.domain(),attachment,now,now);
        jdbc.update("UPDATE file_uploads SET state='completed',attachment_id=?,updated_at=? WHERE id=?",attachment,now,id);
        jdbc.update("UPDATE file_cleanup_jobs SET state='cancelled',updated_at=? WHERE upload_id=? AND state='pending'",now,id);
        return new FileViews.Completed(Long.toString(attachment),"pending",Long.toString(job));
    }
}
