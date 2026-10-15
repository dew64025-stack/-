package com.caizhitong.material.files;

import com.caizhitong.common.*;
import com.caizhitong.common.ratelimit.RateLimiter;
import com.caizhitong.security.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.caizhitong.identity.IdentityInputs.*;

@Service
public class UploadService {
    private final JdbcTemplate jdbc;
    private final SensitiveDataCrypto crypto;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final RateLimiter rates;
    private final ActorLoader actors;
    private final com.caizhitong.integration.storage.ObjectStorage storage;
    public UploadService(JdbcTemplate jdbc,SensitiveDataCrypto crypto,SnowflakeIds ids,Clock clock,PlatformTransactionManager tm,RateLimiter rates,ActorLoader actors,com.caizhitong.integration.storage.ObjectStorage storage) {
        this.jdbc=jdbc;this.crypto=crypto;this.ids=ids;this.clock=clock;this.rates=rates;
        this.tx=new TransactionTemplate(tm);this.tx.setTimeout(10);
        this.actors=actors;this.storage=storage;
    }
    public FileViews.Intent intent(Actor actor,String purpose,String name,String mime,long size,String expected) {
        long max=FilePurpose.authorize(actor,purpose,mime);
        if(size>max)throw new BusinessException(413,"FILE_TOO_LARGE","文件超过大小限制");
        rates.check("file-intent:"+actor.kind()+":"+actor.campusId()+":"+actor.userId(),20,Duration.ofMinutes(1));
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        long id=ids.nextId();Instant expires=clock.instant().plusSeconds(600);
        byte[] key=crypto.encrypt("file-object-key","quarantine/"+UUID.randomUUID());
        tx.executeWithoutResult(s->jdbc.update("INSERT INTO file_uploads(id,campus_id,owner_user_id,access_domain,purpose,file_name,mime_type,size_bytes,expected_sha256,token_digest,expires_at,state,object_key_encrypted,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'issued',?,?,?)",
                id,actor.campusId(),actor.userId(),actor.kind().name().toLowerCase(Locale.ROOT),purpose,name,mime,size,expected,
                HexFormat.of().formatHex(crypto.searchHash("file-upload-token",token)),utc(expires),key,now(),now()));
        return new FileViews.Intent(Long.toString(id),"/api/v1/files/uploads/"+id,token,expires,max);
    }
    public UploadClaim claim(long id,String token) {
        String digest=HexFormat.of().formatHex(crypto.searchHash("file-upload-token",token));
        return tx.execute(s->{
            var rows=jdbc.query("SELECT * FROM file_uploads WHERE id=? AND token_digest=? AND state='issued' AND expires_at>? FOR UPDATE",(r,i)->{
                Actor actor=actors.load(r.getLong("owner_user_id"),r.getLong("campus_id"),SessionKind.valueOf(r.getString("access_domain").toUpperCase(Locale.ROOT))).orElseThrow(UploadCredentialFilter::invalid);
                return new UploadClaim(r.getLong("id"),actor,r.getString("purpose"),r.getString("file_name"),r.getString("mime_type"),r.getLong("size_bytes"),r.getString("expected_sha256"),r.getBytes("object_key_encrypted"));
            },id,digest,now());
            if(rows.isEmpty())throw UploadCredentialFilter.invalid();
            UploadClaim claim=rows.getFirst();
            FilePurpose.authorize(claim.actor(),claim.purpose(),claim.mime());
            if(jdbc.update("UPDATE file_uploads SET state='uploading',updated_at=? WHERE id=? AND state='issued'",now(),id)!=1)throw UploadCredentialFilter.invalid();
            cleanup(claim.encryptedKey(),id,null,now().plusMinutes(10));
            return claim;
        });
    }
    public FileViews.Received receive(UploadClaim claim,org.springframework.web.multipart.MultipartFile file) {
        if(file==null||!claim.fileName().equals(file.getOriginalFilename())||!claim.mime().equals(file.getContentType()))throw invalid();
        if(file.getSize()>claim.size())throw new BusinessException(413,"FILE_TOO_LARGE","文件超过大小限制");
        String key=crypto.decrypt("file-object-key",claim.encryptedKey());
        try(var stream=new BoundedDigestStream(file.getInputStream(),claim.size())) {
            storage.put(key,stream,claim.mime());
            String hash=stream.sha256();
            if(stream.count()!=claim.size()||claim.expectedSha256()!=null&&!claim.expectedSha256().equals(hash))throw invalid();
            tx.executeWithoutResult(s->{if(jdbc.update("UPDATE file_uploads SET state='uploaded',original_sha256=?,updated_at=? WHERE id=? AND state='uploading'",hash,now(),claim.id())!=1)throw FilePurpose.conflict();});
            return new FileViews.Received(Long.toString(claim.id()),true);
        } catch(java.io.IOException e){throw FilePurpose.unavailable();}
    }
    long cleanup(byte[] key,Long upload,Long attachment,LocalDateTime due) {
        long id=ids.nextId();
        jdbc.update("INSERT INTO file_cleanup_jobs(id,object_key_encrypted,upload_id,attachment_id,state,retry_count,next_retry_at,created_at,updated_at) VALUES(?,?,?,?,'pending',0,?,?,?)",id,key,upload,attachment,due,now(),now());
        return id;
    }
    LocalDateTime now(){return utc(clock.instant());}
    static LocalDateTime utc(Instant value){return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
}
