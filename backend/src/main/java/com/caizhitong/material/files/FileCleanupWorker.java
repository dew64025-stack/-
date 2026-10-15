package com.caizhitong.material.files;

import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.integration.storage.ObjectStorage;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component("fileCleanupWorker")
public class FileCleanupWorker implements Runnable {
    private record Candidate(long id,long uploadId){}
    private record Claim(long id,byte[] key,String token,int attempt){}
    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final SensitiveDataCrypto crypto;
    private final Clock clock;
    private final TransactionTemplate tx;
    public FileCleanupWorker(JdbcTemplate jdbc,ObjectStorage storage,SensitiveDataCrypto crypto,Clock clock,PlatformTransactionManager tm){this.jdbc=jdbc;this.storage=storage;this.crypto=crypto;this.clock=clock;tx=new TransactionTemplate(tm);tx.setTimeout(10);}
    @Override public void run(){
        Claim claim=tx.execute(s->claim());if(claim==null)return;
        try {
            storage.delete(crypto.decrypt("file-object-key",claim.key()));
            tx.executeWithoutResult(s->jdbc.update("UPDATE file_cleanup_jobs SET state='succeeded',claim_token=NULL,updated_at=? WHERE id=? AND state='running' AND claim_token=?",now(),claim.id(),claim.token()));
        }catch(RuntimeException e){
            long wait=Math.min(3600,30L<<Math.min(claim.attempt(),6));
            tx.executeWithoutResult(s->jdbc.update("UPDATE file_cleanup_jobs SET state='pending',claim_token=NULL,next_retry_at=?,updated_at=? WHERE id=? AND state='running' AND claim_token=?",now().plusSeconds(wait),now(),claim.id(),claim.token()));
        }
    }
    private Claim claim(){
        String eligible="((state='pending' AND next_retry_at<=?) OR (state='running' AND picked_at<?))";
        var candidates=jdbc.query("SELECT id,upload_id FROM file_cleanup_jobs WHERE "+eligible+" ORDER BY id LIMIT 1",
                (r,i)->new Candidate(r.getLong(1),r.getLong(2)),now(),now().minusMinutes(5));
        if(candidates.isEmpty())return null;
        Candidate candidate=candidates.getFirst();
        // Completion and receive also lock the upload first; do not invert that order when reclaiming a cleanup lease.
        if(candidate.uploadId()!=0)jdbc.queryForList("SELECT id FROM file_uploads WHERE id=? FOR UPDATE",Long.class,candidate.uploadId());
        var rows=jdbc.query("SELECT id,object_key_encrypted,retry_count FROM file_cleanup_jobs WHERE id=? AND "+eligible+" FOR UPDATE SKIP LOCKED",
                (r,i)->new Claim(r.getLong(1),r.getBytes(2),UUID.randomUUID().toString(),r.getInt(3)+1),candidate.id(),now(),now().minusMinutes(5));
        if(rows.isEmpty())return null;Claim c=rows.getFirst();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM file_attachments WHERE object_key_encrypted=? AND state='active'",Integer.class,c.key())>0){jdbc.update("UPDATE file_cleanup_jobs SET state='cancelled',updated_at=? WHERE id=?",now(),c.id());return null;}
        if(candidate.uploadId()!=0)jdbc.update("UPDATE file_uploads SET state='aborted',updated_at=? WHERE id=? AND state IN ('issued','uploading','uploaded')",now(),candidate.uploadId());
        jdbc.update("UPDATE file_cleanup_jobs SET state='running',picked_at=?,claim_token=?,retry_count=retry_count+1,updated_at=? WHERE id=?",now(),c.token(),now(),c.id());return c;
    }
    private LocalDateTime now(){return UploadService.utc(clock.instant());}
}
