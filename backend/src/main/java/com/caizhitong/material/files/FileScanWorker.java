package com.caizhitong.material.files;

import com.caizhitong.common.*;
import com.caizhitong.integration.scanner.*;
import com.caizhitong.integration.storage.ObjectStorage;
import java.io.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component("fileScanWorker")
public class FileScanWorker implements Runnable {
    private record Claim(long job,long attachment,long campus,long owner,String domain,String token,int attempt,int max){}
    private final JdbcTemplate jdbc;
    private final AttachmentService files;
    private final ObjectStorage storage;
    private final VirusScanner scanner;
    private final SensitiveDataCrypto crypto;
    private final Clock clock;
    private final UploadService uploads;
    private final TransactionTemplate tx;
    public FileScanWorker(JdbcTemplate jdbc,AttachmentService files,ObjectStorage storage,VirusScanner scanner,SensitiveDataCrypto crypto,Clock clock,UploadService uploads,PlatformTransactionManager tm){
        this.jdbc=jdbc;this.files=files;this.storage=storage;this.scanner=scanner;this.crypto=crypto;this.clock=clock;this.uploads=uploads;tx=new TransactionTemplate(tm);tx.setTimeout(10);
    }
    @Override public void run(){
        Claim claim=tx.execute(s->claim());if(claim==null)return;
        try {
            var a=files.find(claim.attachment(),false);
            if(a.scope().campusId()!=claim.campus()||a.scope().ownerUserId()!=claim.owner()||!a.scope().accessDomain().equals(claim.domain())||!a.state().equals("active"))throw FilePurpose.missing();
            byte[] raw;
            try(InputStream input=storage.open(crypto.decrypt("file-object-key",a.key()))){raw=input.readNBytes(20*1024*1024+1);}
            if(raw.length>20*1024*1024||raw.length!=a.size()||!FileFormat.sha256(raw).equals(a.originalHash()))throw FilePurpose.conflict();
            ScanResult result=scanner.scan(new ByteArrayInputStream(raw),raw.length);
            if(result==ScanResult.INFECTED){tx.executeWithoutResult(s->infected(claim));return;}
            if(result!=ScanResult.CLEAN)throw FilePurpose.unavailable();
            byte[] cleaned=FileFormat.validateAndSanitize(raw,a.mime(),a.scope().purpose());
            byte[] key=a.key();Long cleanupId=null;
            if(a.mime().startsWith("image/")) {
                key=crypto.encrypt("file-object-key","clean/"+UUID.randomUUID());byte[] newKey=key;
                cleanupId=tx.execute(s->{if(!owns(claim))throw FilePurpose.conflict();long before=uploadsCleanup(newKey);return before;});
                storage.put(crypto.decrypt("file-object-key",key),new ByteArrayInputStream(cleaned),a.mime());
            }
            byte[] newKey=key;Long staged=cleanupId;
            tx.executeWithoutResult(s->{
                if(!owns(claim))return;
                var locked=files.find(claim.attachment(),true);if(!locked.state().equals("active"))throw FilePurpose.conflict();
                jdbc.update("UPDATE file_attachments SET object_key_encrypted=?,content_sha256=?,size_bytes=?,scan_status='clean',updated_at=? WHERE id=?",newKey,FileFormat.sha256(cleaned),cleaned.length,now(),claim.attachment());
                if(staged!=null){jdbc.update("UPDATE file_cleanup_jobs SET state='cancelled',updated_at=? WHERE id=?",now(),staged);uploads.cleanup(a.key(),null,claim.attachment(),now());}
                jdbc.update("UPDATE async_jobs SET status='succeeded',claim_token=NULL,error=NULL,updated_at=? WHERE id=? AND claim_token=?",now(),claim.job(),claim.token());
            });
        }catch(IOException|RuntimeException e){tx.executeWithoutResult(s->failed(claim));}
    }
    private long uploadsCleanup(byte[] key){return uploads.cleanup(key,null,null,now().plusMinutes(10));}
    private Claim claim(){
        jdbc.update("UPDATE async_jobs SET status=CASE WHEN retry_count>=max_retries THEN 'failed' ELSE 'pending' END,claim_token=NULL,next_retry_at=?,updated_at=?,error='SCAN_LEASE_EXPIRED' WHERE job_type='file_scan' AND status='running' AND picked_at<?",now(),now(),now().minusMinutes(5));
        jdbc.update("UPDATE file_attachments SET scan_status='failed',updated_at=? WHERE scan_status='pending' AND id IN (SELECT file_attachment_id FROM async_jobs WHERE job_type='file_scan' AND status='failed')",now());
        var rows=jdbc.query("SELECT id,file_attachment_id,campus_id,owner_user_id,access_domain,retry_count,max_retries FROM async_jobs WHERE job_type='file_scan' AND file_attachment_id IS NOT NULL AND campus_id IS NOT NULL AND owner_user_id IS NOT NULL AND access_domain IN ('student','admin') AND status='pending' AND retry_count<max_retries AND (next_retry_at IS NULL OR next_retry_at<=?) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
                (r,i)->new Claim(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getString(5),UUID.randomUUID().toString(),r.getInt(6)+1,r.getInt(7)),now());
        if(rows.isEmpty())return null;Claim c=rows.getFirst();jdbc.update("UPDATE async_jobs SET status='running',picked_at=?,claim_token=?,retry_count=retry_count+1,updated_at=? WHERE id=?",now(),c.token(),now(),c.job());return c;
    }
    private boolean owns(Claim c){return !jdbc.queryForList("SELECT id FROM async_jobs WHERE id=? AND status='running' AND claim_token=? FOR UPDATE",Long.class,c.job(),c.token()).isEmpty();}
    private void infected(Claim c){if(!owns(c))return;jdbc.update("UPDATE file_attachments SET scan_status='infected',updated_at=? WHERE id=?",now(),c.attachment());jdbc.update("UPDATE async_jobs SET status='failed',error='FILE_INFECTED',claim_token=NULL,updated_at=? WHERE id=?",now(),c.job());}
    private void failed(Claim c){if(!owns(c))return;jdbc.update("UPDATE file_attachments SET scan_status='failed',updated_at=? WHERE id=? AND scan_status<>'infected'",now(),c.attachment());jdbc.update("UPDATE async_jobs SET status=?,next_retry_at=?,claim_token=NULL,error='SCAN_FAILED',updated_at=? WHERE id=?",c.attempt()>=c.max()?"failed":"pending",now().plusSeconds(30),now(),c.job());}
    private LocalDateTime now(){return UploadService.utc(clock.instant());}
}
